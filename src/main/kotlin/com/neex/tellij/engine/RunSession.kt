package com.neex.tellij.engine

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.DocumentUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.neex.tellij.Keys
import com.neex.tellij.editor.BlockGutter
import com.neex.tellij.model.Block
import com.neex.tellij.model.WorkbenchModel
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.RunContext
import com.neex.tellij.spi.SourceRegistry
import com.neex.tellij.spi.StepError
import com.neex.tellij.vars.EnvFile
import com.neex.tellij.vars.VarEngine
import com.neex.tellij.vars.Vars
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** The `@var` values a document is currently running with; invalidated when the header changes. */
class VarsSnapshot(val vars: Vars, val headerHash: Int)

/**
 * One running workbench per document. Created on the EDT (writes the "running" sections), executes
 * on pooled threads with the plugin classloader as TCCL (Kafka/AWS/Mongo use ServiceLoader), and
 * finishes back on the EDT: final headers, document saved to disk, gutters refreshed.
 */
class RunSession private constructor(
    val project: Project,
    val document: Document,
    override val vars: Vars,
) : Disposable, RunContext {

    private val cancelled = AtomicBoolean(false)
    @Volatile var finished = false
        private set
    private val workers = CopyOnWriteArraySet<Thread>()
    private val sinks = HashMap<Int, DocumentResultSink>()
    internal val finishListeners = ArrayList<() -> Unit>()

    override val isCancelled: Boolean get() = cancelled.get()

    override fun sleep(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (!isCancelled) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            try {
                Thread.sleep(minOf(50L, left))
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            workers.forEach { it.interrupt() }
        }
    }

    override fun dispose() = cancel()

    private fun <T> withPluginClassLoader(body: () -> T): T {
        val thread = Thread.currentThread()
        val old = thread.contextClassLoader
        thread.contextClassLoader = RunSession::class.java.classLoader
        try {
            return body()
        } finally {
            thread.contextClassLoader = old
        }
    }

    private fun spawn(r: Runnable) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val t = Thread.currentThread()
            workers += t
            try {
                withPluginClassLoader { r.run() }
            } catch (e: Throwable) {
                LOG.warn("tellij worker failed", e)
            } finally {
                workers -= t
                Thread.interrupted() // clear a pending interrupt before the thread returns to the pool
            }
        }
    }

    @RequiresEdt
    private fun finish() {
        if (finished) return
        finished = true
        sinks.values.forEach { it.flushNow() }
        if (document.getUserData(Keys.SESSION) === this) document.putUserData(Keys.SESSION, null)
        if (!project.isDisposed) {
            FileDocumentManager.getInstance().saveDocument(document)
            BlockGutter.refreshAll(document)
        }
        finishListeners.forEach { runCatching { it() } }
        Disposer.dispose(this)
    }

    companion object {
        private val LOG = Logger.getInstance(RunSession::class.java)

        fun liveFor(document: Document): RunSession? = document.getUserData(Keys.SESSION)?.takeIf { !it.finished }

        fun cancelFor(document: Document) {
            liveFor(document)?.cancel()
        }

        /**
         * Parses, snapshots variables, writes a "running" section under every selected block and
         * starts execution. Returns null (with a StepError to show) when nothing can run.
         */
        @RequiresEdt
        fun start(project: Project, document: Document, select: (WorkbenchModel) -> List<Block>, freshVars: Boolean): RunSession? {
            if (liveFor(document) != null) throw StepError("a run is in progress", "Esc stops it")
            if (!document.isWritable) throw StepError("file is read-only", "results are written into the file")
            val text = document.immutableCharSequence
            val model = WorkbenchParser.parse(text)
            val blocks = select(model)
            if (blocks.isEmpty()) return null

            val vars = snapshot(document, model, text, freshVars)
            val plan = Runner.plan(text, model, blocks, vars, SourceRegistry::byId)
            val session = RunSession(project, document, vars)
            Disposer.register(TellijProjectScope.get(project), session)
            document.putUserData(Keys.SESSION, session)

            val at = Vars.timestamp()
            DocumentUtil.writeInRunUndoTransparentAction {
                // Last block first so earlier offsets stay valid while we insert.
                for (entry in plan.entries.asReversed()) {
                    val block = entry.block
                    val blockText = text.subSequence(block.range.start, block.result?.start ?: block.range.end)
                    val used = vars.referencedValues(blockText)
                    val range = ResultWriter.write(document, block, ResultWriter.initialSection(block, used, at))
                    val limit = block.directive("limit")?.toIntOrNull() ?: DocumentResultSink.DEFAULT_MAX_LINES
                    session.sinks[block.index] = DocumentResultSink(session, document, block, range, used, limit)
                }
            }
            BlockGutter.refreshAll(document)

            session.spawn {
                try {
                    Runner.execute(plan, session, { session.sinks.getValue(it.index) }, session::spawn)
                } finally {
                    ApplicationManager.getApplication().invokeLater({ session.finish() }, ModalityState.nonModal())
                }
            }
            return session
        }

        private fun snapshot(document: Document, model: WorkbenchModel, text: CharSequence, fresh: Boolean): Vars {
            val headerHash = text.subSequence(0, model.headerRange.end).hashCode()
            val existing = document.getUserData(Keys.VARS)
            if (!fresh && existing != null && existing.headerHash == headerHash) return existing.vars
            val vars = VarEngine.resolve(model, EnvFile.load(directoryOf(document)))
            document.putUserData(Keys.VARS, VarsSnapshot(vars, headerHash))
            return vars
        }

        fun directoryOf(document: Document): Path? {
            val vf = FileDocumentManager.getInstance().getFile(document) ?: return null
            if (!vf.isInLocalFileSystem) return null
            return runCatching { Path.of(vf.path).parent }.getOrNull()
        }
    }
}
