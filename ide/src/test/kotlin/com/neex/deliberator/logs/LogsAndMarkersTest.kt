package com.neex.deliberator.logs

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.run.RunView
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Logs and markers against the real daemon on a temp home: a log file in the estate's logback pattern
 * (`%d %-5level APP [%X{correlation}] %logger{36}:%line - %msg`) fetched through `logs.fetch` with an absolute-file
 * target, and a fixture `com.example.demo.Inbox` whose line 6 is the marked one. No estate.
 */
class LogsAndMarkersTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var markers: MarkerService
    private lateinit var matcher: LogMatcher

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        svc.bind("w1")
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "L-1") }) }
        markers = MarkerService.getInstance(project)
        matcher = LogMatcher.getInstance(project)
        matcher.reset()
    }

    override fun tearDown() {
        try {
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)
    private fun wait(what: String, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, 120)

    private fun fixture(rel: String, text: String): VirtualFile {
        val p = daemon.home.resolve("fixture").resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(p.toString())!!
    }

    private val inbox = """
        |package com.example.demo;
        |
        |public class Inbox {
        |    static class Inner { void x() { } }
        |    void handle(String m) {
        |        System.out.println(m);
        |    }
        |}
        |""".trimMargin()

    private fun ts(n: Int) = "2026-10-16T10:00:%02d.123+03:00".format(n)

    /** 1 run1 Inbox:6 | 2 run1 Inbox:6 ERROR | 3-4 its stack | 5 other run Inbox:6 | 6 run1 com.other Inbox:6 | 7 run1 Inbox:7 | 8 run1-002 Inbox:6 */
    private val logText = listOf(
        "${ts(1)} INFO  APP [run1-001] com.example.demo.Inbox:6 - received m1",
        "${ts(2)} ERROR APP [run1-001] com.example.demo.Inbox:6 - boom",
        "java.lang.IllegalStateException: boom",
        "\tat com.example.demo.Inbox.handle(Inbox.java:6)",
        "${ts(3)} INFO  APP [zzz9-001] com.example.demo.Inbox:6 - received other run",
        "${ts(4)} INFO  APP [run1-001] com.other.demo.Inbox:6 - same line, another class",
        "${ts(5)} INFO  APP [run1-001] com.example.demo.Inbox:7 - another line",
        "${ts(6)} INFO  APP [run1-002] com.example.demo.Inbox:6 - second step",
    ).joinToString("\n") + "\n"

    private fun fetched(): Pair<VirtualFile, String> {
        val src = fixture("app.log", logText)
        val r = bg { LogService.getInstance(project).fetchNow(FetchRequest(src.path)) }
        val path = r.get("path").asString
        assertFalse("the daemon wrote its own file", src.path == path)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(path)!! to path
    }

    private fun openLog(): Editor {
        val (_, path) = fetched()
        val ed = matcher.open(path, 1)!!
        assertEquals(path, FileDocumentManager.getInstance().getFile(ed.document)!!.path)
        return ed
    }

    private fun openInbox(): Editor {
        val vf = fixture("src/com/example/demo/Inbox.java", inbox)
        myFixture.openFileInEditor(vf)
        return myFixture.editor
    }

    private fun markLine6(ed: Editor) {
        ToggleMarkerAction.toggle(project, ed, 5)
        wait("marker stored") { markers.markers.size == 1 }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun listFromDaemon() = Marker.parse(bg { WorkspaceService.getInstance(project).call("marker.list").result })

    /** The visible lines of the 8 log lines (the empty line after the final newline is not one). */
    private fun vis(ed: Editor) = CorrelationFilter.visibleLines(ed).filter { it <= 8 }

    private fun settle(f: java.util.concurrent.CompletableFuture<Unit>) = wait("annotation") { f.isDone }

    // ---------------------------------------------------------------------------------------------

    fun testActionsAndTheJavaResolverAreRegistered() {
        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        for (id in listOf("deliberator.logs.fetch", "deliberator.logs.filter", "deliberator.logs.unfilter", "deliberator.logs.match", "deliberator.marker.toggle"))
            assertNotNull(id, am.getAction(id))
        val gutter = am.getAction("EditorGutterPopupMenu") as com.intellij.openapi.actionSystem.ActionGroup
        assertTrue("the toggle is in the gutter menu", gutter.getChildren(null).any { it === am.getAction("deliberator.marker.toggle") })
        assertTrue(LoggerResolver.EP.extensionList.any { it is JavaLoggerResolver })
    }

    fun testFetchOpensTheFileTheDaemonWroteWithItsContents() {
        val (vf, path) = fetched()
        assertEquals(logText, String(vf.contentsToByteArray()))
        assertTrue(path.endsWith(".log"))
        val ed = matcher.open(path, 1)!!
        assertEquals(logText, ed.document.text)
        assertTrue("the opened file is a log", LogFiles.isLog(FileDocumentManager.getInstance().getFile(ed.document)!!))
        assertTrue("log targets of the env come from env.describe", bg { LogService.getInstance(project).targets() }.contains("log/service-a"))
    }

    fun testCorrelationFilterFoldsOtherEntriesInPlaceAndCounts() {
        val ed = openLog()
        val before = ed.document.text
        val st = CorrelationFilter.apply(ed, project, "run1")
        assertEquals(before, ed.document.text)
        assertEquals("6 entries, one has another correlation", 6, st.total)
        assertEquals(5, st.kept)
        assertEquals(1, st.hiddenLines)
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8), vis(ed))
        assertEquals(st, CorrelationFilter.stateOf(ed))
        // the platform's test status bar keeps no text: when it does, it is ours
        WindowManager.getInstance().getStatusBar(project)?.info?.let { assertEquals(st.status, it) }
        assertTrue(st.status, st.status.contains("5 of 6"))

        // the stack trace belongs to its entry: filtering on a word of the trace keeps the whole entry
        val st2 = CorrelationFilter.apply(ed, project, "IllegalStateException")
        assertEquals(1, st2.kept)
        assertEquals(listOf(2, 3, 4), vis(ed))
        assertEquals("the entry before and the entries after it", 2, st2.regions)
        assertEquals(6, st2.hiddenLines)

        CorrelationFilter.clear(ed, project)
        assertEquals((1..8).toList(), vis(ed))
        assertNull(CorrelationFilter.stateOf(ed))
        assertEquals(before, ed.document.text)
    }

    fun testFilterIdComesFromSelectionThenLatestRunThenThePrompt() {
        val ed = openLog()
        var asked = 0
        val ask = { _: String? -> asked++; "typed" }
        assertEquals("typed", com.neex.deliberator.logs.FilterByCorrelationAction.idFor(project, ed, ask))
        assertEquals(1, asked)
        val start = ed.document.text.indexOf("zzz9-001")
        ed.selectionModel.setSelection(start, start + "zzz9-001".length)
        assertEquals("zzz9-001", FilterByCorrelationAction.idFor(project, ed, ask))
        assertEquals(1, asked)
    }

    fun testMarkerIsStoredThroughTheDaemonWithTheClassFqnAndSurvivesARestart() {
        val ed = openInbox()
        markLine6(ed)
        val m = markers.markers.single()
        assertEquals("com.example.demo.Inbox", m.logger)
        assertEquals(6, m.line)
        assertEquals(m, listFromDaemon().single())
        assertEquals("the gutter shows it", setOf(6), MarkerGutter.of(ed)!!.icons().keys)

        bg { daemon.launcher.stopDaemon(daemon.home) }
        val again = bg { markers.refreshNow() }
        assertEquals("a new daemon still has the marker", listOf(m), again)
    }

    fun testAFileWithoutAJavaClassSendsNoLogger() {
        val vf = fixture("notes.txt", "one\ntwo\n")
        myFixture.openFileInEditor(vf)
        ToggleMarkerAction.toggle(project, myFixture.editor, 1)
        wait("marker stored") { markers.markers.size == 1 }
        val m = markers.markers.single()
        assertEquals(2, m.line)
        assertNull(m.logger)
    }

    fun testMatchHighlightsTheRightLinesOnly() {
        val ed = openInbox()
        markLine6(ed)
        val log = openLog()
        settle(matcher.annotate(log))
        // lines 1, 2, the stack frame (4), the other run's entry (5) and step 2 (8); not com.other (6), not line 7
        assertEquals(listOf(1, 2, 4, 5, 8), matcher.highlights(log).map { it.n })
        assertTrue(matcher.highlights(log).all { it.markerId == markers.markers.single().id })
        val hs = log.markupModel.allHighlighters.filter { it.textAttributesKey == LogMatcher.MATCHED }
        assertEquals(listOf(0, 1, 3, 4, 7), hs.map { log.document.getLineNumber(it.startOffset) }.sorted())
        assertEquals("marker com.example.demo.Inbox:6", hs.first().errorStripeTooltip)
        assertEquals("the file is untouched", logText, log.document.text)
    }

    fun testTheIconNavigatesToTheLastMatchedLineAndRemoveClearsEverything() {
        val ed = openInbox()
        markLine6(ed)
        val log = openLog()
        settle(matcher.annotate(log))
        val gutter = MarkerGutter.of(ed)!!
        val icon = gutter.icons().getValue(6)
        assertTrue(icon.tooltipText, icon.tooltipText!!.contains("last matched line 8"))

        myFixture.openFileInEditor(FileDocumentManager.getInstance().getFile(ed.document)!!)
        assertTrue(gutter.navigate(icon.marker))
        val now = FileEditorManagerCurrent.editor(project)
        assertEquals(log.document.text, now.document.text)
        assertEquals("caret on the last matched line", 8, now.caretModel.logicalPosition.line + 1)

        // remove through the icon's popup menu
        val remove = icon.popupMenuActions!!.getChildren(null).single()
        remove.actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent(remove, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()))
        wait("marker removed") { markers.markers.isEmpty() && listFromDaemon().isEmpty() }
        wait("icons and highlights cleared") { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); gutter.icons().isEmpty() && matcher.highlights(log).isEmpty() }
        assertNull(matcher.lastMatch(icon.marker.id).takeIf { matcher.highlights(log).isNotEmpty() })
    }

    fun testAFinishedRunAnnotatesTheOpenLogs() {
        val ed = openInbox()
        val log = openLog()
        settle(matcher.annotate(log))
        assertTrue(matcher.highlights(log).isEmpty())
        // a marker added behind the plugin's back (no change event), then a real run finishes
        bg { WorkspaceService.getInstance(project).call("marker.add", JsonObject().apply { addProperty("file", FileDocumentManager.getInstance().getFile(ed.document)!!.path); addProperty("line", 6); addProperty("logger", "com.example.demo.Inbox") }) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(matcher.highlights(log).isEmpty())

        val scn = fixture("run.scn", "### wait one\n# @for 100ms\n")
        myFixture.openFileInEditor(scn)
        val view = RunView.of(myFixture.editor)!!
        val runner = ScnRunner.getInstance(project)
        view.runSteps(null)
        wait("the run ends") { !runner.isRunning(scn.path) && runner.state(scn.path).snapshot != null }
        wait("the open log is annotated after the run") { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); matcher.highlights(log).size == 5 }
        assertNotNull(matcher.latestRun)
        // the run's id is in no line of this log: the filter asks instead of folding everything (B3)
        val runId = matcher.latestRun!!.runId
        assertFalse(log.document.text.contains(runId))
        assertNull(FilterByCorrelationAction.idFor(project, log) { null })
    }

    fun testMarkersFollowEditsOnSaveAndADeletedLineLeavesItsMarkerAlone() {
        val ed = openInbox()
        markLine6(ed)
        val gutter = MarkerGutter.of(ed)!!
        WriteCommandAction.runWriteCommandAction(project) { ed.document.insertString(0, "// header\n") }
        assertEquals("the highlighter moved with the text", setOf(7), gutter.icons().filter { it.value.marker.line == 6 }.map { 7 }.toSet())
        assertEquals(1, bg { gutter.syncMoved() })
        wait("moved marker") { markers.markers.singleOrNull()?.line == 7 }
        assertEquals(7, listFromDaemon().single().line)
        assertEquals("com.example.demo.Inbox", listFromDaemon().single().logger)

        // delete the whole marked line: nothing to follow, the marker stays at line 7, no silent drop
        WriteCommandAction.runWriteCommandAction(project) {
            val d = ed.document
            d.deleteString(d.getLineStartOffset(6), d.getLineEndOffset(6) + 1)
        }
        assertEquals(0, bg { gutter.syncMoved() })
        assertEquals(7, listFromDaemon().single().line)
    }
}

private object FileEditorManagerCurrent {
    fun editor(project: com.intellij.openapi.project.Project): Editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor!!
}
