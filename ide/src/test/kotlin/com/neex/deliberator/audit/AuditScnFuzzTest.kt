package com.neex.deliberator.audit

import com.google.gson.JsonObject
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.scn.ScnFileType
import com.neex.deliberator.scn.ScnRegistry
import com.neex.deliberator.testing.TempDaemon
import java.util.Random

/**
 * Differential fuzz of the editor's lexer/PSI against the daemon's parser (the "the editor never parses twice differently" promise of
 * the implementation plan): seeded random scenarios built from head, directive, quote, brace, blank and CRLF lines; wherever
 * `scenario.parse` accepts the text, the PSI must find the same steps on the same lines.
 */
class AuditScnFuzzTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        ScnRegistry.getInstance().reset()
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

    private val heads = listOf("### wait a", "### wait", "### http", "### http GET x", "### sql db/main q", "### sqs send queue/inbox s", "###", "### ", "###x", "   ### wait x",
        "#### wait", "### 9bad", "\t### wait", "### wait é", "### logs log/a", "### wait x   ")
    private val directives = listOf("# @for 1ms", "# @name foo", "# @assert elapsed >= 0", "# comment", "#", "# @timeout 1s", "  # @for 2ms", "#@for 3ms", "# @capture a = \$.x")
    private val bodies = listOf("{", "{\"a\": \"x", "\"", "}", "select 1;", "{\"a\": \"###\"}", "[1,", "  \"###\",", "x\"y", "", "   ", "\t", "{\"k\": [", "]}", "plain text ### not a head", "'''", "é ü ß",
        "{\"a\":\"b\\\"c\"}", "\\")
    private val headerLines = listOf("# @env local", "# @var a = 1", "# a comment", "", "   ")

    private val oneShotDirectives = listOf("# @for 1ms", "# @name foo", "# @timeout 1s", "# @assert elapsed >= 0")

    /** Header, then steps: a head, up to 2 distinct directives, and body lines that may contain quotes, braces, `###`-looking lines and blanks. */
    private fun doc(rnd: Random): String {
        val lines = ArrayList<String>()
        if (rnd.nextInt(3) == 0) repeat(rnd.nextInt(3)) { lines += headerLines[rnd.nextInt(headerLines.size)].takeIf { l -> l != "# @env local" || lines.none { it == l } } ?: "" }
        repeat(1 + rnd.nextInt(6)) {
            lines += heads[rnd.nextInt(heads.size)]
            val ds = oneShotDirectives.shuffled(rnd).take(rnd.nextInt(3))
            lines += ds
            if (rnd.nextInt(4) == 0) lines += directives[rnd.nextInt(directives.size)].takeIf { !it.contains("@") } ?: "# note"
            repeat(rnd.nextInt(5)) { lines += bodies[rnd.nextInt(bodies.size)] }
        }
        val eol = if (rnd.nextInt(6) == 0) "\r\n" else "\n"
        return lines.joinToString(eol) + (if (rnd.nextBoolean()) eol else "")
    }

    fun testThePsiFindsTheStepsScenarioParseFindsOnRandomScenarios() {
        val client = daemon.client
        val factory = PsiFileFactory.getInstance(project)
        var compared = 0
        var rejected = 0
        val why = java.util.TreeMap<String, Int>()
        val diffs = ArrayList<String>()
        for (seed in 1..600) {
            val text = doc(Random(seed.toLong()))
            val r = client.call("scenario.parse", JsonObject().apply { addProperty("text", text) }).resultObject()
            if (r.getAsJsonArray("errors").size() > 0) {
                rejected++
                why.merge(r.getAsJsonArray("errors")[0].asJsonObject.get("message").asString.replace(Regex("[0-9]+"), "N").take(60), 1, Int::plus)
                continue
            }
            compared++
            val psi = factory.createFileFromText("fz$seed.scn", ScnFileType, text) as ScnFile
            val core = r.getAsJsonArray("steps").map { it.asJsonObject }
            val mine = psi.steps
            val sameCount = core.size == mine.size
            val same = sameCount && core.indices.all { k ->
                core[k].get("line").asInt == mine[k].startLine && core[k].get("end-line").asInt == mine[k].endLine && core[k].get("kind").asString == mine[k].kind
            }
            if (!same) diffs += "seed $seed: core ${core.map { "${it.get("kind").asString}@${it.get("line")}-${it.get("end-line")}" }} psi ${mine.map { "${it.kind}@${it.startLine}-${it.endLine}" }}\n--- text:\n${text.replace("\r", "\\r")}"
        }
        println("AUDIT-FUZZ: 600 scenarios, $compared accepted by scenario.parse and compared, $rejected rejected; ${diffs.size} differences; rejected because: $why")
        assertTrue("the PSI and scenario.parse disagree on ${diffs.size} of $compared:\n" + diffs.take(3).joinToString("\n====\n"), diffs.isEmpty())
        assertTrue("fuzz produced too few valid scenarios: $compared", compared >= 100)
    }
}
