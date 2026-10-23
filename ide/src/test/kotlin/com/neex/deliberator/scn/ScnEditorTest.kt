package com.neex.deliberator.scn

import com.google.gson.JsonObject
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.TimeUnit

/**
 * The `.scn` editor features against the real daemon (jar on a temp home): the PSI's step boundaries against
 * `scenario.parse` of the same text for every fixture, folding, structure view, completion with and without the
 * daemon, and the parse error annotation.
 */
class ScnEditorTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        ScnRegistry.getInstance().reset()
        WorkspaceService.getInstance(project).loadState(WorkspaceService.State())
    }

    override fun tearDown() {
        try {
            ScnRegistry.getInstance().reset()
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)

    private fun args(vararg kv: Pair<String, String>) = JsonObject().also { o -> kv.forEach { o.addProperty(it.first, it.second) } }

    private fun scn(text: String, name: String = "a.scn") = myFixture.configureByText(name, text) as ScnFile

    /** Brings the daemon up and loads the registry and the targets of workspace `w1` (what opening an .scn editor does). */
    private fun online() {
        daemon.openWorkspace("w1")
        WorkspaceService.getInstance(project).bind("w1")
        bg { ScnRegistry.getInstance().refresh(); ScnRegistry.getInstance().refreshTargets(project) }
    }

    // golden: step boundaries ------------------------------------------------------------------------

    private fun assertBoundaries(name: String, text: String) {
        val psi = scn(text, "$name.scn")
        val parsed = daemon.client.call("scenario.parse", args("text" to text)).resultObject()
        val errors = parsed.getAsJsonArray("errors")
        assertEquals("$name: the daemon rejects the test data: $errors", 0, errors.size())
        val steps = parsed.getAsJsonArray("steps")
        assertEquals("$name: number of steps", steps.size(), psi.steps.size)
        steps.forEachIndexed { i, e ->
            val s = e.asJsonObject
            val p = psi.steps[i]
            assertEquals("$name step ${i + 1}: index", s.get("index").asInt, p.index)
            assertEquals("$name step ${i + 1}: line", s.get("line").asInt, p.startLine)
            assertEquals("$name step ${i + 1}: end-line", s.get("end-line").asInt, p.endLine)
            assertEquals("$name step ${i + 1}: kind", s.get("kind").asString, p.kind)
        }
    }

    /**
     * Two core fixtures (`order`, `crlf`) test the parser with kinds only the engine tests register (`tail`, `fake`)
     * and their directives; swapped for real ones with the same line structure so the daemon accepts the text.
     */
    private fun forTheRegistry(t: String) = t.replace(Regex("(?m)^### (tail|fake)(?=\\s)"), "### wait")
        .replace(Regex("(?m)^# @(set|log|display|fail)(?=\\s)"), "# @name")

    fun testStepBoundariesAgreeWithScenarioParseOnEveryFixture() {
        val fixtures = ScnTestData.fixtures()
        assertTrue("fixtures: ${fixtures.keys}", fixtures.size >= 6)
        for ((n, t) in fixtures.entries) assertBoundaries(n, forTheRegistry(t))
    }

    fun testStepBoundariesAgreeOnTheCorners() {
        for ((n, t) in ScnTestData.corners.entries) assertBoundaries(n, t)
    }

    fun testStepWordsFollowTheRegistryOnceItIsKnown() {
        val text = "### scheduler list list-schedules\n### sqs send queue/inbox send-order\n### http create-order\n"
        val psi = scn(text)
        // lexical rule: `list list-schedules` has no target-like word, so no verb is seen
        assertNull(psi.steps[0].verb)
        online()
        assertEquals("list", psi.steps[0].verb)
        assertEquals("list-schedules", psi.steps[0].stepName)
        assertNull(psi.steps[0].target)
        assertEquals("send", psi.steps[1].verb)
        assertEquals("queue/inbox", psi.steps[1].target)
        assertEquals("send-order", psi.steps[1].stepName)
        assertEquals("create-order", psi.steps[2].stepName)
    }

    // folding, structure ----------------------------------------------------------------------------

    fun testOrderScnFoldsPerStepAndKeepsTheHeaderLine() {
        val text = ScnTestData.fixtures().getValue("estate-order")
        val psi = scn(text)
        val doc = myFixture.editor.document
        val regions = ScnFoldingBuilder().buildFoldRegions(psi, doc, false)
        val steps = psi.steps
        assertEquals(9, steps.size)
        val stepRegions = regions.filter { it.element.psi is ScnStep }
        assertEquals(9, stepRegions.size)
        for ((i, r) in stepRegions.withIndex()) {
            val headLine = doc.getLineNumber(steps[i].textRange.startOffset)
            assertEquals("region $i starts on the head line, after its text", headLine, doc.getLineNumber(r.range.startOffset))
            assertEquals(doc.getLineEndOffset(headLine), r.range.startOffset)
            assertTrue("region $i spans lines", doc.getLineNumber(r.range.endOffset) > headLine)
            assertFalse("region $i ends on content", text[r.range.endOffset - 1].isWhitespace())
        }
        assertEquals("one for the multi-line header block", 1, regions.count { it.element.psi is ScnHeader })
    }

    fun testStructureViewListsTheStepsAsTheirHeadText() {
        val psi = scn(ScnTestData.fixtures().getValue("estate-order"))
        val model = ScnStructureViewModel(psi, myFixture.editor)
        try {
            val kids = model.root.children.map { it.presentation.presentableText }
            assertEquals(9, kids.size)
            assertEquals("sqs send queue/inbox send-order", kids[0])
            assertEquals("dynamodb get table/messages stored", kids[1])
            assertEquals("http publish", kids[2])
            assertEquals("logs log/logger lambda-log", kids[8])
        } finally {
            model.dispose()
        }
    }

    // completion -----------------------------------------------------------------------------------

    /** Completes at the caret and takes the item [text] (fuzzy matching may leave several, so the lookup is not always auto-finished). */
    private fun choose(text: String) {
        myFixture.completeBasic()
        val lookup = myFixture.lookup ?: return
        lookup.currentItem = lookup.items.first { it.lookupString == text }
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
    }

    private fun withCaret(): String {
        val d = myFixture.editor.document.text
        val c = myFixture.editor.caretModel.offset
        return d.substring(0, c) + "<caret>" + d.substring(c)
    }

    private fun complete(text: String): List<String> {
        myFixture.configureByText("c.scn", text)
        val items = myFixture.completeBasic()
        return items?.map { it.lookupString } ?: myFixture.lookupElementStrings ?: emptyList()
    }

    fun testKindsAndVerbsAndTargetsComeFromTheDaemon() {
        online()
        val kinds = complete("### <caret>")
        assertTrue(kinds.toString(), kinds.containsAll(listOf("http", "sqs", "dynamodb", "sql", "logs", "wait", "lambda", "eventbridge", "scheduler")))
        assertEquals(listOf("receive", "send"), complete("### sqs <caret>").sorted())
        val targets = complete("### sqs send <caret>")
        assertTrue(targets.toString(), targets.contains("queue/inbox"))
        assertTrue(complete("### sqs send queue/inbox <caret>").isEmpty())
        // a kind with an optional verb offers verbs and targets
        assertTrue(complete("### http <caret>").contains("service/a"))
        // a lone prefix narrows
        assertEquals(listOf("sqs", "sql").sorted(), complete("### sq<caret>").sorted())
    }

    fun testDirectiveNamesFactsAndOperators() {
        online()
        val names = complete("### http create\n# @<caret>")
        assertTrue(names.toString(), names.containsAll(listOf("assert", "capture", "until", "timeout", "body", "name")))
        val httpFacts = complete("### http create\n# @assert <caret>")
        assertTrue(httpFacts.toString(), httpFacts.containsAll(listOf("status", "elapsed")))
        assertTrue(complete("### http create\n# @capture id = <caret>").containsAll(listOf("status", "$")))
        assertTrue(complete("### http create\n# @assert status <caret>").containsAll(listOf("==", "contains", "matches")))
        // the sqs kind's own directive, and a non-repeatable one already used is not offered again
        assertTrue(complete("### sqs send queue/inbox\n# @<caret>").contains("attr"))
        assertFalse(complete("### http x\n# @timeout 5s\n# @<caret>").contains("timeout"))
        assertTrue(complete("### http x\n# @assert status == 1\n# @<caret>").contains("assert"))
        // before the first step: the header's own
        assertEquals(listOf("env", "var"), complete("# @<caret>\n### wait").sorted())
    }

    fun testVariablesHeaderVarsCapturesAndBuiltins() {
        online()
        val text = """
            # @var orderId = ord-1
            ### http a
            # @capture id = ${'$'}.id
            GET {{service/a}}/x
            ### http b
            # @capture later = ${'$'}.x
            GET {{<caret>}}
            ### http c
            # @capture notyet = ${'$'}.x
        """.trimIndent()
        val vars = complete(text)
        assertTrue(vars.toString(), vars.containsAll(listOf("orderId", "id", "later", "uuid", "now", "now.epoch", "correlation", "step.correlation", "service/a")))
        assertFalse(vars.toString(), vars.contains("notyet"))
        // choosing one keeps a single closing }} and leaves the caret after it
        myFixture.configureByText("v.scn", "# @var abc = 1\n### wait\nx {{ab<caret>}}\n")
        choose("abc")
        assertEquals("# @var abc = 1\n### wait\nx {{abc}}<caret>\n", withCaret())
        myFixture.configureByText("v2.scn", "# @var abc = 1\n### wait\nx {{ab<caret>\n")
        choose("abc")
        assertEquals("# @var abc = 1\n### wait\nx {{abc}}<caret>\n", withCaret())
    }

    fun testOfflineTheStaticPartStillAnswers() {
        // no daemon was ever reached in this test: nothing cached, and completion must not start one
        assertNull(ScnRegistry.getInstance().kinds())
        val names = complete("### http create\n# @<caret>")
        assertEquals(setOf("assert", "capture", "until", "timeout", "body", "name"), names.toSet())
        val vars = complete("# @var q = 1\n### wait\n{{<caret>}}")
        assertTrue(vars.toString(), vars.containsAll(listOf("q", "uuid", "correlation", "now.epoch")))
        assertTrue(complete("### <caret>").isEmpty())
        assertNull("completion did not start a daemon", daemon.lockPid())
    }

    fun testTheLastRegistryIsKeptForTheNextSession() {
        online()
        val reg = ScnRegistry.getInstance()
        val saved = reg.state
        assertTrue(saved.json.isNotBlank())
        // a new IDE process: the live copy is gone, the saved one is loaded
        val fresh = ScnRegistry()
        fresh.loadState(saved)
        assertTrue(fresh.kinds()!!["sqs"]!!.verbs.contains("send"))
        assertFalse(fresh.isLive())
    }

    // annotations ----------------------------------------------------------------------------------

    fun testParseErrorIsUnderlinedOnItsLine() {
        online()
        myFixture.configureByText("e.scn", "# @env local\n\n### nosuchkind x\nbody\n")
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        val doc = myFixture.editor.document
        assertEquals(errors.toString(), 1, errors.size)
        assertEquals(2, doc.getLineNumber(errors[0].startOffset))
        assertTrue(errors[0].description, errors[0].description.contains("unknown step kind"))
    }

    fun testAValidScenarioHasNoErrors() {
        online()
        myFixture.configureByText("ok.scn", ScnTestData.fixtures().getValue("plain"))
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    // highlighting ---------------------------------------------------------------------------------

    fun testTheFileTypeIsRegisteredAndTheLexerColoursTheTokens() {
        assertSame(ScnFileType, com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName("order.scn"))
        val hl = ScnSyntaxHighlighter()
        assertEquals(ScnColors.KIND, hl.getTokenHighlights(ScnTokens.KIND).single())
        assertEquals(ScnColors.VARIABLE, hl.getTokenHighlights(ScnTokens.VARIABLE).single())
        assertEquals(ScnColors.DIR_NAME, hl.getTokenHighlights(ScnTokens.DIR_NAME).single())
        assertEquals(ScnColors.COMMENT, hl.getTokenHighlights(ScnTokens.COMMENT).single())
    }

    fun testVerbAndNameAreColouredByTheRegistryOnceKnown() {
        online()
        myFixture.configureByText("h.scn", "### scheduler list list-schedules\n### sqs send queue/inbox send-order\n")
        val text = myFixture.editor.document.text
        val coloured = myFixture.doHighlighting().filter { it.forcedTextAttributesKey != null }
            .associate { text.substring(it.startOffset, it.endOffset) to it.forcedTextAttributesKey }
        assertEquals(ScnColors.VERB, coloured["list"])
        assertEquals(ScnColors.NAME, coloured["list-schedules"])
        assertEquals(ScnColors.VERB, coloured["send"])
        assertEquals(ScnColors.NAME, coloured["send-order"])
    }

    /** The audit's 5 000-step file: PSI build plus walking every step's lines; the number goes into. */
    fun testFiveThousandStepsPsi() {
        val text = buildString {
            append("# @env local\n# @var a = 1\n\n")
            for (i in 1..5000) append("### sqs send queue/inbox step-$i\n# @attr id={{a}}\n# @assert messageId matches /x/\n{\"order\": \"A-$i\", \"qty\": 2}\n\n")
        }
        val factory = com.intellij.psi.PsiFileFactory.getInstance(project)
        val ms = (1..5).map {
            val t0 = System.nanoTime()
            val f = factory.createFileFromText("big.scn", ScnFileType, text) as ScnFile
            val steps = f.steps
            var sum = 0L
            for (st in steps) sum += st.endLine - st.startLine
            assertEquals(5000, steps.size)
            (System.nanoTime() - t0) / 1_000_000.0
        }.sorted()
        println("SCN-PSI-5000: median ms=${ms[2]} min=${ms[0]} max=${ms[4]}")
        assertTrue("5000 steps: ${ms[2]} ms", ms[2] < 5000)
    }

    fun testOpBodiesAreJsonAndShellBodiesAreLeftAloneWithoutTheShellPlugin() {
        val text = "### op marker.add\n{\"file\": \"{{f}}\", \"line\": 1}\n\n### shell\necho hi\n"
        val f = scn(text)
        val ilm = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project)
        assertEquals("JSON", ilm.findInjectedElementAt(f, text.indexOf("\"line\"") + 1)?.language?.id)
        val sh = ilm.findInjectedElementAt(f, text.indexOf("echo") + 1)?.language?.id
        assertTrue("shell body: Shell Script when the sh plugin is loaded, nothing otherwise, never JSON ($sh)", sh == null || sh == "Shell Script")
    }
}
