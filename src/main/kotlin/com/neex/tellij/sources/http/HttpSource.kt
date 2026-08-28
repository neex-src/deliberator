package com.neex.tellij.sources.http

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.spi.CaptureSpec
import com.neex.tellij.spi.ConnectContext
import com.neex.tellij.spi.DirectiveSpec
import com.neex.tellij.spi.Connection
import com.neex.tellij.spi.Durations
import com.neex.tellij.spi.ResultSink
import com.neex.tellij.spi.RunContext
import com.neex.tellij.spi.SourceKind
import com.neex.tellij.spi.Step
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * `### http` runs the block body as an `.http`-style request with the JDK client and writes the
 * response under it — status line, headers, body (JSON pretty-printed). No `@connect` is needed;
 * `# @connect http api base=http://host:port` makes relative URLs work and can carry shared
 * headers (`header.Authorization="Bearer …"`). The bundled HTTP client is deliberately not used:
 * it renders into its own tool window, and this plugin's whole point is the buffer.
 */
class HttpSource : SourceKind {
    override val id: String = "http"

    override val directives = listOf(
        DirectiveSpec("header", "Name=value request header (or write `Name: value` under the request line)"),
        DirectiveSpec("capture", "name = \$.path[0].to.value from the JSON response"),
    )
    override val connectOptions = listOf(
        DirectiveSpec("base", "base URL for relative requests", listOf("http://localhost:8085")),
        DirectiveSpec("header.", "shared header, e.g. header.Authorization=\"Bearer …\""),
        DirectiveSpec("timeout", "request timeout", listOf("10s", "30s")),
    )

    override fun defaultConnection(): ConnectDecl = ConnectDecl("http", "default", emptyMap(), -1)

    override fun connect(decl: ConnectDecl, ctx: ConnectContext): Connection = HttpConnection(
        decl,
        base = decl.option("base")?.trimEnd('/'),
        timeoutMs = Durations.parse(decl.option("timeout"), 10_000),
        headers = decl.options.filterKeys { it.startsWith("header.") }.map { (k, v) -> k.removePrefix("header.") to v },
    )

    override fun step(req: StepRequest, conn: Connection): Step {
        val c = conn as HttpConnection
        val spec = HttpRequestParser.parse(req.body)
        val url = if (spec.url.startsWith("http://") || spec.url.startsWith("https://")) spec.url
        else c.base?.let { it + (if (spec.url.startsWith("/")) spec.url else "/${spec.url}") }
            ?: throw StepError("relative URL '${spec.url}' needs a base", "# @connect http api base=http://localhost:8085")
        val headers = c.headers + spec.headers + req.directives("header").map { h ->
            val i = h.indexOf('='); if (i <= 0) throw StepError("bad header '$h'", "# @header Name=value")
            h.substring(0, i).trim() to h.substring(i + 1).trim()
        }
        return HttpSend(c, spec.copy(url = url, headers = headers), Durations.parse(req.directive("timeout"), c.timeoutMs), CaptureSpec.parse(req.directives("capture")))
    }
}

class HttpConnection(override val decl: ConnectDecl, val base: String?, val timeoutMs: Long, val headers: List<Pair<String, String>>) : Connection {
    val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
}

class HttpSend(private val conn: HttpConnection, private val spec: HttpRequestSpec, private val timeoutMs: Long, private val captures: List<CaptureSpec>) : Step {
    override fun run(ctx: RunContext, out: ResultSink) {
        val uri = try { URI.create(spec.url) } catch (e: Exception) { throw StepError("bad URL '${spec.url}'") }
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs))
        for ((k, v) in spec.headers) builder.header(k, v)
        val bodyPublisher = spec.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        builder.method(spec.method, bodyPublisher)
        val response: HttpResponse<String> = try {
            conn.client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw StepError("no response from ${uri.host}:${uri.port} within $timeoutMs ms", "# @timeout 30s")
        } catch (e: ConnectException) {
            throw StepError("cannot reach ${uri.host}:${uri.port}: ${e.message ?: "connection refused"}", "is the service up? demo/up.sh consumer")
        } catch (e: java.io.IOException) {
            throw StepError("request failed: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.first()}")
        }
        val version = if (response.version() == HttpClient.Version.HTTP_2) "HTTP/2" else "HTTP/1.1"
        out.appendLine("$version ${response.statusCode()} ${reason(response.statusCode())}".trimEnd())
        response.headers().map().toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (k, v) -> if (!k.startsWith(":")) out.appendLine("$k: ${v.joinToString(", ")}") }
        val body = response.body()
        val contentType = response.headers().firstValue("content-type").orElse("")
        if (body.isNotEmpty()) {
            out.appendLine("")
            out.appendText(if (contentType.contains("json", ignoreCase = true)) prettyJson(body) else body)
        }
        for (c in captures) ctx.capture(c.name, JsonPathLite.get(body, c.expr))
        out.done("${response.statusCode()} · ${size(body.toByteArray().size)}")
    }

    private fun prettyJson(body: String): String = try {
        GSON.toJson(JsonParser.parseString(body))
    } catch (e: Exception) {
        body
    }

    private fun size(bytes: Int): String = if (bytes < 1024) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
        private val REASONS = mapOf(
            200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content", 301 to "Moved Permanently", 302 to "Found",
            304 to "Not Modified", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 409 to "Conflict", 415 to "Unsupported Media Type", 422 to "Unprocessable Entity",
            429 to "Too Many Requests", 500 to "Internal Server Error", 502 to "Bad Gateway", 503 to "Service Unavailable", 504 to "Gateway Timeout",
        )
        fun reason(code: Int): String = REASONS[code] ?: ""
    }
}
