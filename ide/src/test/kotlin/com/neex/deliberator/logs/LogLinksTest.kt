package com.neex.deliberator.logs

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * From a log line to the code against the real daemon on a temp home: three directories attached as repositories
 * (`billing-repo` with the Spring service `billing`, `other-repo` with a second `com.acme.Pricing`, `lambdas` with the node-lambda
 * `thrower`), logs written where the daemon's own naming says whose they are, real mouse events on the editor's component.
 */
class LogLinksTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var links: LogLinks

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        svc.bind("w1")
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "L-1") }) }
        links = LogLinks.getInstance(project)
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
    private fun call(op: String, vararg kv: Pair<String, String>) =
        bg { WorkspaceService.getInstance(project).call(op, JsonObject().apply { kv.forEach { (k, v) -> addProperty(k, v) } }).resultObject() }

    private fun fixture(rel: String, text: String): VirtualFile {
        val p = daemon.home.resolve("fixture").resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(p.toString())!!
    }

    private val pricing = (1..14).joinToString("") { "// line $it\n" } + "public class Pricing { int cents() { return 1; } }\n// 16\n"
    private val exceptionClass = "package com.acme;\n\npublic class PricingException extends RuntimeException {\n}\n"

    /** Attaches the repositories and adopts the services; returns nothing, the daemon holds it. */
    private fun estate() {
        fixture("billing-repo/src/main/java/com/acme/Pricing.java", pricing)
        fixture("billing-repo/src/main/java/com/acme/PricingException.java", exceptionClass)
        fixture("billing-repo/deliberator-service.edn", "{:name \"billing\" :kind :spring-boot :port 8082 :image \"docker.io/library/eclipse-temurin:21-jre\"}\n")
        fixture("other-repo/src/main/java/com/acme/Pricing.java", pricing)
        fixture("lambdas/functions/thrower/index.mjs", "export const handler = async () => {\n  const x = 1;\n  throw new Error('boom');\n};\n")
        fixture("lambdas/functions/thrower/deliberator-service.edn", "{:name \"thrower\" :kind :node-lambda}\n")
        for (r in listOf("billing-repo", "other-repo", "lambdas")) call("repo.attach", "path" to daemon.home.resolve("fixture").resolve(r).toString())
        call("service.adopt", "path" to daemon.home.resolve("fixture/billing-repo").toString())
        call("service.adopt", "path" to daemon.home.resolve("fixture/lambdas/functions/thrower").toString())
    }

    private val spring = listOf(
        "2026-11-05T10:00:00.100+01:00 ERROR billing [c1] c.acme.Pricing:15 - boom",
        "com.acme.PricingException: no price for LEGACY",
        "\tat com.acme.Pricing.firstInvoiceCents(Pricing.java:15)",
        "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)",
        "Caused by: com.acme.PricingException: inner",
    ).joinToString("\n") + "\n"

    /** A log in the runtime's mirror layout: the daemon reads the service from the directory name. */
    private fun serviceLog(role: String, text: String): VirtualFile =
        fixtureAt(daemon.home.resolve("workspaces/w1/logs/services/$role/2026-11-05.log"), text)

    private fun fixtureAt(p: java.nio.file.Path, text: String): VirtualFile {
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(p.toString())!!
    }

    private fun openLog(vf: VirtualFile, expectLinks: Int): Editor {
        myFixture.openFileInEditor(vf)
        val ed = myFixture.editor
        wait("$expectLinks links in ${vf.name}") { links.links(ed).size == expectLinks }
        return ed
    }

    private fun offsetOf(ed: Editor, text: String, nth: Int = 0): Int {
        var at = -1
        repeat(nth + 1) { at = ed.document.text.indexOf(text, at + 1) }
        assertTrue("'$text' in the log", at >= 0)
        return at
    }

    /** A real click: pressed, released, clicked on the editor's component at the middle of the character at [offset]. */
    private fun click(ed: Editor, offset: Int, ctrl: Boolean = false) {
        val p = ed.offsetToXY(offset)
        val x = p.x + 3
        val y = p.y + ed.lineHeight / 2
        val mods = if (ctrl) InputEvent.CTRL_DOWN_MASK else 0
        val c = ed.contentComponent
        for (id in intArrayOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            c.dispatchEvent(MouseEvent(c, id, System.currentTimeMillis(), mods, x, y, 1, false, MouseEvent.BUTTON1))
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun selected(): Editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    private fun selectedName() = FileDocumentManager.getInstance().getFile(selected().document)!!.name
    private fun caretLine(ed: Editor) = ed.caretModel.logicalPosition.line + 1

    fun testTheDaemonsPlacesAreUnderlinedAndThePlainFrameOfALibraryIsNot() {
        estate()
        val ed = openLog(serviceLog("service-billing", spring), 4)
        assertEquals(listOf("c.acme.Pricing:15", "com.acme.PricingException", "Pricing.java:15", "com.acme.PricingException"), links.links(ed).map { it.text })
        assertEquals(listOf("logger", "exception", "frame", "exception"), links.links(ed).map { it.kind })
        assertTrue("billing's own repository is the only target", links.links(ed).all { r -> r.targets.size == 1 && r.targets[0].repo == "billing-repo" })
        val lib = offsetOf(ed, "DispatcherServlet.java:1089")
        assertNull("a class of no attached repository is not a link", links.linkAt(ed, lib))
        assertNotNull(links.linkAt(ed, offsetOf(ed, "Pricing.java:15") + 3))
        assertEquals("billing-repo: src/main/java/com/acme/Pricing.java:15  -  Ctrl+click to open", links.tooltip(links.links(ed)[2]))
    }

    fun testCtrlClickOpensTheSourceLineAPlainClickOnlyPlacesTheCaret() {
        estate()
        val log = serviceLog("service-billing", spring)
        val ed = openLog(log, 4)
        val frame = offsetOf(ed, "Pricing.java:15") + 2
        click(ed, frame)
        assertEquals("a plain click does not leave the log", log.name, selectedName())
        click(ed, frame, ctrl = true)
        assertEquals("Pricing.java", selectedName())
        assertTrue(FileDocumentManager.getInstance().getFile(selected().document)!!.path.contains("/billing-repo/"))
        assertEquals(15, caretLine(selected()))
    }

    fun testTheLoggerAndLineTokenOpensItsLineAndTheExceptionClassItsDeclaration() {
        estate()
        val ed = openLog(serviceLog("service-billing", spring), 4)
        click(ed, offsetOf(ed, "c.acme.Pricing:15") + 4, ctrl = true)
        assertEquals("Pricing.java", selectedName())
        assertEquals(15, caretLine(selected()))
        click(ed, offsetOf(ed, "com.acme.PricingException") + 5, ctrl = true)
        // the click may not reach the log editor any more (the source is selected): select the log again
        FileEditorManager.getInstance(project).openFile(FileDocumentManager.getInstance().getFile(ed.document)!!, true)
        click(ed, offsetOf(ed, "com.acme.PricingException") + 5, ctrl = true)
        assertEquals("PricingException.java", selectedName())
        val src = selected()
        assertEquals("the caret is on the class name (Java plugin's locator)", src.document.text.indexOf("PricingException extends"), src.caretModel.offset)
    }

    fun testALogNobodyOwnsOffersEveryRepositoryThatHoldsTheClass() {
        estate()
        val ed = openLog(fixture("app.log", spring), 4)
        val frame = links.links(ed).first { it.kind == "frame" }
        assertEquals(listOf("billing-repo", "other-repo"), frame.targets.map { it.repo })
        assertEquals("2 files (billing-repo, other-repo)  -  Ctrl+click to open", links.tooltip(frame))
        val other = links.open(frame, frame.targets[1])!!
        assertTrue(FileDocumentManager.getInstance().getFile(other.document)!!.path.contains("/other-repo/"))
        assertEquals(15, caretLine(other))
    }

    fun testTheKeyboardRouteGoesThroughTheActionOnTheCaretLine() {
        estate()
        val log = serviceLog("service-billing", spring)
        val ed = openLog(log, 4)
        ed.caretModel.moveToOffset(ed.document.getLineStartOffset(2) + 1) // the frame line, caret in the tab
        myFixture.performEditorAction("deliberator.logs.gotoSource")
        assertEquals("Pricing.java", selectedName())
        assertEquals(15, caretLine(selected()))
    }

    fun testANodeFrameOfALambdaOpensItsSource() {
        estate()
        val stack = "{\"errorType\":\"Error\",\"errorMessage\":\"boom\",\"stack\":[\"Error: boom\",\"    at Runtime.handler (file:///var/task/index.mjs:3:9)\"," +
            "\"    at async Runtime.handleOnceNonStreaming (file:///var/runtime/index.mjs:1205:29)\"]}\n"
        val ed = openLog(serviceLog("service-thrower", stack), 1)
        val l = links.links(ed)[0]
        assertEquals("node", l.kind)
        assertEquals("/var/task/index.mjs:3:9", l.text)
        assertNull("/var/runtime is nobody's", links.linkAt(ed, offsetOf(ed, "/var/runtime/index.mjs")))
        click(ed, offsetOf(ed, "/var/task/index.mjs") + 3, ctrl = true)
        assertEquals("index.mjs", selectedName())
        assertEquals(3, caretLine(selected()))
        assertEquals(8, selected().caretModel.logicalPosition.column)
    }

    fun testLinksFollowEditsAndAnUnattachedRepositoryGivesNone() {
        estate()
        val ed = openLog(serviceLog("service-billing", spring), 4)
        WriteCommandAction.runWriteCommandAction(project) { ed.document.insertString(0, "a new first line\n") }
        // the daemon reads the file: an unsaved edit would shift the lines under its answer, and the editor refuses to draw a link at a wrong place
        FileDocumentManager.getInstance().saveDocument(ed.document)
        wait("redrawn after the edit") { links.links(ed).map { it.n } == listOf(2, 3, 4, 6) }
        assertEquals(listOf("c.acme.Pricing:15", "com.acme.PricingException", "Pricing.java:15", "com.acme.PricingException"), links.links(ed).map { it.text })
        for (r in listOf("billing-repo", "other-repo", "lambdas")) call("repo.detach", "name" to r)
        myFixture.openFileInEditor(fixture("elsewhere.log", spring))
        val bare = myFixture.editor
        val answered = links.annotate(bare)
        wait("the daemon answered") { answered.isDone }
        assertTrue(links.links(bare).isEmpty())
    }
}
