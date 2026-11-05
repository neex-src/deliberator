package com.neex.deliberator.run

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.scn.ScnLexer
import com.neex.deliberator.scn.ScnTokens
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Prose to steps from the editor against the real daemon, with the fixture model of core/test/fixtures
 * (a babashka script that answers canned text; no real model). The steps are `wait` steps, so the run needs no estate.
 */
class GenerateFromEditorTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var runner: ScnRunner
    private lateinit var fixtures: Path
    private val scenarioPaths = ArrayList<String>()

    private val prose = "### ai pause\nWait a little, then wait a little more.\n"
    private val doc = "# @env local\n\n$prose\n### wait later\n# @for 100ms\n"

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("w1")
        svc.bind("w1")
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "G-1") }) }
        runner = ScnRunner.getInstance(project)
        fixtures = Files.createDirectories(daemon.home.resolve("fx"))
        val model = Path.of("..", "core", "test", "fixtures", "fixture-model.bb").toAbsolutePath().normalize()
        val edn = daemon.home.resolve("workspaces").resolve("w1").resolve("workspace.edn")
        val text = Files.readString(edn).trimEnd().removeSuffix("}")
        Files.writeString(edn, "$text :ai {:command [\"bb\" \"$model\"] :env {\"FIXTURE_DIR\" \"$fixtures\"} :format :claude-json :budget-ms 60000}}")
    }

    override fun tearDown() {
        try {
            scenarioPaths.forEach { p -> if (runner.isRunning(p)) runner.cancel(p) }
            com.neex.deliberator.scn.ScnRegistry.getInstance().reset()
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)
    private fun wait(what: String, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, 120)
    private fun answers(vararg a: String) = a.forEachIndexed { i, t -> Files.writeString(fixtures.resolve("answer-${i + 1}.txt"), t) }

    private fun open(text: String): Pair<VirtualFile, RunView> {
        val path = daemon.scenario("g.scn", text)
        scenarioPaths += path
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)!!
        myFixture.openFileInEditor(vf)
        val view = RunView.of(myFixture.editor)!!
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return vf to view
    }

    private fun dataContext() = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .add(CommonDataKeys.PSI_FILE, PsiManager.getInstance(project).findFile(FileDocumentManager.getInstance().getFile(myFixture.editor.document)!!))
        .build()

    private fun perform(id: String): Boolean {
        val a = ActionManager.getInstance().getAction(id)
        val ev = TestActionEvent.createTestEvent(a, dataContext())
        a.update(ev)
        if (ev.presentation.isEnabledAndVisible) a.actionPerformed(ev)
        return ev.presentation.isEnabledAndVisible
    }

    private fun text() = myFixture.editor.document.text

    private fun caretAt(needle: String) = myFixture.editor.caretModel.moveToOffset(text().indexOf(needle))

    private fun generate(name: String = "pause") {
        val before = text()
        caretAt("Wait a little")
        assertTrue("Generate is offered at the caret in the prose", perform("deliberator.scenario.generate"))
        wait("generate $name to end") { !GenerateRun.isRunning(myFixture.file.virtualFile, name) && text() != before }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    // ---------------------------------------------------------------------------------------------

    fun testGenerateWritesTheStepsUnderTheProseAndTheyRunInline() {
        answers("### wait first\n# @for 100ms\n\n### wait second\n# @for 100ms\n")
        val (vf, view) = open(doc)
        assertEquals("an ai head has the Generate icon, not a run icon", setOf(1), view.aiGutterIcons().keys)
        assertFalse(view.gutterIcons().containsKey(1))
        assertTrue(view.gutterIcons().containsKey(2))
        generate()
        val t = text()
        assertTrue(t, t.startsWith("# @env local\n\n### ai pause\nWait a little, then wait a little more.\n\n#> generated by fixture-haiku · 3 004 tokens in, 10 out · \$0.0123 · "))
        assertTrue(t, t.contains("\n### wait first\n# @for 100ms\n\n### wait second\n# @for 100ms\n#< end\n\n### wait later"))
        val psi = PsiManager.getInstance(project).findFile(vf) as ScnFile
        assertEquals(listOf("ai", "wait", "wait", "wait"), psi.steps.map { it.kind })
        assertFalse("the end marker is not part of the last generated step", psi.steps[2].text.contains("#<"))
        assertFalse("the marker is not part of the prose", psi.steps[0].body?.text.orEmpty().contains("#>"))
        // run: the ai block is skipped, the three waits run, results sit under their steps
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val before = runner.state(vf.path).snapshot
        view.runSteps(null)
        wait("the run to end") { !runner.isRunning(vf.path) && runner.state(vf.path).snapshot.let { it != null && it !== before } }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val snap = runner.state(vf.path).snapshot!!
        assertEquals("ok", snap.status)
        assertEquals(setOf(2, 3, 4), snap.sections.keys)
        assertEquals(listOf(2, 3, 4), view.inlayInfos().mapNotNull { it.step })
    }

    fun testGenerateAgainReplacesTheRegion() {
        answers("### wait first\n# @for 100ms\n", "### wait other\n# @for 100ms\n")
        open(doc)
        generate()
        val once = text()
        generate()
        val twice = text()
        assertTrue(twice, twice.contains("### wait other"))
        assertFalse(twice.contains("### wait first"))
        assertEquals("one region", 1, twice.lines().count { it.startsWith("#> generated") })
        assertEquals(1, twice.lines().count { it == "#< end" })
        assertNotSame(once, twice)
    }

    fun testWhileTheModelWorksOtherEditsDoNotMoveTheRegion() {
        answers("### wait first\n# @for 100ms\n")
        open(doc)
        val before = text()
        caretAt("Wait a little")
        perform("deliberator.scenario.generate")
        // an edit above the block while the model is at work
        ApplicationManager.getApplication().runWriteAction { myFixture.editor.document.insertString(0, "# a comment\n") }
        wait("generate to end") { !GenerateRun.isRunning(myFixture.file.virtualFile, "pause") && text().contains("#> generated") }
        val t = text()
        assertTrue(t, t.startsWith("# a comment\n# @env local\n"))
        assertTrue(t, t.contains("Wait a little, then wait a little more.\n\n#> generated by"))
        assertNotSame(before, t)
    }

    fun testAWrongAnswerChangesNothing() {
        answers("### sqz send queue/inbox x\n{}\n")
        val (vf, _) = open(doc)
        val before = text()
        caretAt("Wait a little")
        perform("deliberator.scenario.generate")
        val runs = daemon.home.resolve("workspaces").resolve("w1").resolve("sessions").resolve("G-1").resolve("generate")
        wait("the model was asked twice") { Files.isDirectory(runs) && Files.list(runs).use { s -> s.anyMatch { Files.exists(it.resolve("answer-2.scn")) } } }
        wait("generate to end") { !GenerateRun.isRunning(vf, "pause") }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(before, text())
    }

    fun testTheGenerateActionIsOfferedInTheProseAndInTheGeneratedStepsOnly() {
        answers("### wait first\n# @for 100ms\n")
        open(doc)
        caretAt("# @env")
        assertFalse("not in the header", perform("deliberator.scenario.generate"))
        caretAt("### wait later")
        assertFalse("not in a plain step", perform("deliberator.scenario.generate"))
        generate()
        caretAt("### wait first")
        val ev = TestActionEvent.createTestEvent(ActionManager.getInstance().getAction("deliberator.scenario.generate"), dataContext())
        ActionManager.getInstance().getAction("deliberator.scenario.generate").update(ev)
        assertTrue("in the generated steps: replaces them", ev.presentation.isEnabledAndVisible)
        caretAt("### wait later")
        assertFalse("after the end marker it is a plain step again", perform("deliberator.scenario.generate"))
    }

    fun testMarkerLinesAreTheirOwnTokenAndInsideAJsonStringTheyAreBody() {
        fun kinds(s: String): List<Pair<String, String>> {
            val lx = ScnLexer(); lx.start(s, 0, s.length, 0)
            val out = ArrayList<Pair<String, String>>()
            while (lx.tokenType != null) { out += lx.tokenType.toString() to s.substring(lx.tokenStart, lx.tokenEnd); lx.advance() }
            return out
        }
        val t = kinds("### ai a\nprose\n#> generated by m\n### wait w\n#< end\n")
        assertTrue(t.toString(), t.contains("MARKER" to "#> generated by m"))
        assertTrue(t.toString(), t.contains("MARKER" to "#< end"))
        val j = kinds("### http h\nPOST http://x/\n\n{\"a\": \"one\n#> not a marker\nline\"}\n")
        assertFalse(j.toString(), j.any { it.first == "MARKER" })
        assertNotNull(ScnTokens.MARKER)
    }

    fun testOpenUniversalJsonWritesAndOpensIt() {
        open(doc)
        assertTrue(perform("deliberator.universal.open"))
        wait("universal.json to open") { FileEditorManager.getInstance(project).selectedFiles.any { it.name == "universal.json" } }
        val f = FileEditorManager.getInstance(project).selectedFiles.first { it.name == "universal.json" }
        assertEquals(daemon.home.resolve("workspaces").resolve("w1").resolve("universal.json").toRealPath().toString(), Path.of(f.path).toRealPath().toString())
        val json = String(f.contentsToByteArray())
        assertTrue(json.contains("\"step-kinds\""))
        assertFalse("the ai kind is not offered to the model", json.contains("\"kind\":\"ai\""))
    }
}
