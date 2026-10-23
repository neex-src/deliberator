package com.neex.deliberator.scn

import java.io.File

/** The `.scn` test data: the core fixtures and the estate scenario (copied into `scn/` by Gradle) plus corner cases written here. */
object ScnTestData {
    /** name -> text of every .scn under scn/ on the test classpath (`estate-order` is `estate/scenarios/order.scn`). */
    fun fixtures(): Map<String, String> {
        val dir = File(ScnTestData::class.java.getResource("/scn")!!.toURI())
        return dir.listFiles { f -> f.name.endsWith(".scn") }!!.sortedBy { it.name }
            .associate { it.name.removeSuffix(".scn") to String(it.readBytes(), Charsets.UTF_8) }
    }

    /** Valid scenarios (the daemon parses them) that stress the step boundaries. */
    val corners: Map<String, String> = linkedMapOf(
        "prose-among-directives" to """
            ### wait nap
            # Waits, and this line explains why.
            # @for 1s

            # More prose after a blank line.
            ### sqs send queue/inbox
            # What this sends.
            # @attr id=1
            hello
            # body: the body had started
            """.trimIndent() + "\n",
        "json-string-hashes" to """
            ### sql db/main q1
            { "a": "x
            ### sqs send queue/inbox
            " }
            ### sql db/main q2
            select 1;
        """.trimIndent() + "\n",
        "unclosed-string" to """
            ### sql db/main q1
            { "a": "x
            ### sql db/main q2
            select 1;
        """.trimIndent() + "\n",
        "no-trailing-newline" to "### wait\n# @for 1s",
        "trailing-blank-lines" to "### wait\n# @for 1s\n\n\n\n",
        "trailing-blank-spaces" to "### wait\n# @for 1s\n   \n",
        "header-only" to "# @env local\n# only a header\n",
        "empty" to "",
        "single-head" to "### wait",
        "blank-between-heads" to "### wait a\n\n\n### wait b\n\n### wait c\n",
        "body-comment-zone" to "### sql db/main q\n# not a directive\n# plain comment\nselect 1;\n",
        "indented-and-quoted-heads" to "### sql db/main q\nselect 1;\n   ### indented\n#### four\n### wait\n",
        "crlf" to "### wait a\r\n# @for 1s\r\n\r\n### wait b\r\n",
    )

    /** Inputs of the lexer goldens that need no daemon: every header form and directive/variable/body shape. */
    val lexerForms: Map<String, String> = linkedMapOf(
        "header-forms" to """
            ### http
            ### http create-order
            ### http {{service/a}}
            ### http https://example.org/x name
            ### sqs send queue/inbox send-order
            ### scheduler list list-schedules
            ### wait   
            ###nospace is body
            ### 9bad is body
               ### indented is body
            #### four hashes are body
        """.trimIndent() + "\n",
        "header-region" to """
            # a comment
            // a slash comment
            # @env local
            #@var a = {{uuid}}
            # @var b=x{{a}}y

            stray text
            ### wait
        """.trimIndent() + "\n",
        "directive-zone" to """
            ### logs log/service-a   
            # @filter {{correlation}}  

              # @for 10s
            # @assert lines >= 2
            # a comment ends the zone
            # @assert now a body line
            {"k": "{{v}} and {{unclosed"}
        """.trimIndent() + "\n",
        "json-string-rule" to """
            ### sql db/main q1
            { "a": "x
            ### sqs send queue/inbox
            " }
            ### wait
        """.trimIndent() + "\n",
        "crlf" to "### wait a\r\n# @for 1s\r\nbody {{x}}\r\n\r\n### wait b\r\n",
    )
}
