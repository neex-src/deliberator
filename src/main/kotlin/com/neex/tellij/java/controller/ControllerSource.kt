package com.neex.tellij.java.controller

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.neex.tellij.java.endpoint.DtoShape
import com.neex.tellij.java.endpoint.Endpoint
import com.neex.tellij.java.endpoint.EndpointResolver
import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.sources.http.HttpConnection
import com.neex.tellij.sources.http.HttpRequestSpec
import com.neex.tellij.sources.http.HttpSend
import com.neex.tellij.sources.http.HttpSource
import com.neex.tellij.spi.CaptureSpec
import com.neex.tellij.spi.ConnectContext
import com.neex.tellij.spi.Connection
import com.neex.tellij.spi.DirectiveSpec
import com.neex.tellij.spi.Durations
import com.neex.tellij.spi.SourceKind
import com.neex.tellij.spi.Step
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import java.net.URLEncoder

/**
 * `### controller OrdersController.create`: the controller method is resolved by PSI, its HTTP
 * method and path come from the same resolver as the endpoint summary, the body is the JSON for
 * its `@RequestBody` (comments allowed), and the request goes out through the `http` machinery
 * to the running app. Reading code to build a request, so a field renamed in Java is a field
 * renamed here too.
 */
class ControllerSource : SourceKind {
    override val id: String = "controller"

    override val directives = listOf(
        DirectiveSpec("path", "name=value for a {path variable} (repeatable)", listOf("id={{orderId}}")),
        DirectiveSpec("query", "name=value query parameter (repeatable)", listOf("verbose=true")),
        DirectiveSpec("header", "Name=value request header (repeatable)", listOf("X-Trace={{orderId}}")),
        DirectiveSpec("method", "overload to call, e.g. create(CreateOrderRequest)"),
        DirectiveSpec("capture", "name = \$.path from the JSON response"),
        DirectiveSpec("assert", "facts: status, headers.<name>, body, size, \$.json.path, endpoint, elapsed", listOf("status == 201", "\$.status == \"NEW\"", "\$.errors[0].field == \"customer\"")),
    )
    override val connectOptions = listOf(
        DirectiveSpec("base", "where the app listens", listOf("http://localhost:8080")),
        DirectiveSpec("header.", "shared header, e.g. header.Authorization=\"Bearer …\""),
        DirectiveSpec("timeout", "request timeout", listOf("10s", "30s")),
    )

    override fun defaultConnection(): ConnectDecl = ConnectDecl("controller", "default", mapOf("base" to "http://localhost:8080"), -1)

    override fun connect(decl: ConnectDecl, ctx: ConnectContext): Connection =
        HttpSource().connect(decl.copy(kind = "http"), ctx)

    override fun step(req: StepRequest, conn: Connection): Step {
        val http = conn as HttpConnection
        val project = req.context["project"] as? Project ?: throw StepError("controller blocks need the IDE project", "run this block from the editor")
        val ref = req.block.name ?: throw StepError("no controller method", "### controller OrdersController.create")
        val endpoint = ReadAction.compute<Endpoint, StepError> {
            val method = EndpointResolver.findMethod(project, ref, req.directive("method"))
            EndpointResolver.resolve(method) ?: throw StepError("$ref is not a Spring MVC handler", "no @RequestMapping/@GetMapping/… on it or on the interface it implements")
        }
        val base = http.base ?: throw StepError("controller connection needs base=", "# @connect controller api base=http://localhost:8080")
        val pathVars = pairs(req.directives("path"), "path")
        val path = Regex("""\{([^}/]+)}""").replace(endpoint.path) { m ->
            val name = m.groupValues[1].substringBefore(':')
            URLEncoder.encode(pathVars[name] ?: throw StepError("path variable '$name' not set", "# @path $name={{$name}}"), Charsets.UTF_8)
        }
        val query = req.directives("query").map { kv -> val (k, v) = kvOf(kv, "query"); "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val url = base + path + (if (query.isEmpty()) "" else "?" + query.joinToString("&"))
        val body = JsonComments.strip(req.body).trim().ifEmpty { null }
        val headers = ArrayList(http.headers + pairs(req.directives("header"), "header").toList())
        if (body != null && headers.none { it.first.equals("Content-Type", true) }) headers += "Content-Type" to "application/json"
        if (headers.none { it.first.equals("Accept", true) }) headers += "Accept" to "application/json"
        val spec = HttpRequestSpec(endpoint.method, url, headers, body)
        val preface = listOf("${endpoint.method} $url   (${endpoint.impl.display})") + headers.map { "${it.first}: ${it.second}" } + listOf("")
        return HttpSend(http, spec, Durations.parse(req.directive("timeout"), http.timeoutMs), CaptureSpec.parse(req.directives("capture")), preface, mapOf("endpoint" to endpoint.title))
    }

    private fun kvOf(s: String, what: String): Pair<String, String> {
        val i = s.indexOf('=')
        if (i <= 0) throw StepError("bad @$what '$s'", "# @$what name=value")
        return s.substring(0, i).trim() to s.substring(i + 1).trim()
    }

    private fun pairs(values: List<String>, what: String): Map<String, String> = values.associate { kvOf(it, what) }

    companion object {
        /** The DTO shape a JSON path points at: object keys descend into nested shapes, array indices stay. */
        fun shapeAt(root: DtoShape?, path: List<Any>): DtoShape? {
            var shape = root ?: return null
            for (p in path) {
                if (p is Int) continue
                shape = shape.fields.firstOrNull { it.name == p }?.nested ?: return null
            }
            return shape
        }
    }
}
