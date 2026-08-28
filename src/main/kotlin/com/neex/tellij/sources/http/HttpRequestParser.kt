package com.neex.tellij.sources.http

import com.neex.tellij.spi.StepError

data class HttpRequestSpec(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: String?)

/**
 * The `.http` request shape: `METHOD URL [HTTP/1.1]`, `Name: value` header lines, a blank line,
 * then the body. A first line without a method is a GET.
 */
object HttpRequestParser {
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE")

    fun parse(text: String): HttpRequestSpec {
        val lines = text.lines().dropWhile { it.isBlank() }
        if (lines.isEmpty()) throw StepError("empty request", "GET http://localhost:8085/health")
        val first = lines[0].trim().split(Regex("\\s+"))
        val method: String
        val url: String
        if (first[0].uppercase() in METHODS) {
            method = first[0].uppercase()
            url = first.getOrNull(1) ?: throw StepError("request line has no URL", "$method http://…")
        } else {
            method = "GET"
            url = first[0]
        }
        val headers = ArrayList<Pair<String, String>>()
        var i = 1
        while (i < lines.size && lines[i].isNotBlank()) {
            val l = lines[i]
            val c = l.indexOf(':')
            if (c <= 0) throw StepError("bad header line '$l'", "Name: value — or leave a blank line before the body")
            headers += l.substring(0, c).trim() to l.substring(c + 1).trim()
            i++
        }
        val body = lines.drop(i + 1).joinToString("\n").trim().ifEmpty { null }
        return HttpRequestSpec(method, url, headers, body)
    }
}
