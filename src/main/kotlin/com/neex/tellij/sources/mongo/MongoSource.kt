package com.neex.tellij.sources.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoException
import com.mongodb.MongoTimeoutException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.sources.http.JsonPathLite
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
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.Document
import org.bson.json.JsonMode
import org.bson.json.JsonParseException
import org.bson.json.JsonWriterSettings
import java.util.concurrent.TimeUnit

/**
 * `### mongo <collection>` with a JSON body: a filter for `find` (default), the document(s) for
 * `# @op insert`, a pipeline for `# @op aggregate`, a filter for `# @op count`. The document half
 * of the DB category, next to Postgres to show the seam spans shapes: documents render as pretty
 * relaxed extended JSON, one per paragraph.
 */
class MongoSource : SourceKind {
    override val id: String = "mongo"

    override val directives = listOf(
        DirectiveSpec("op", "what to do with the body", listOf("find", "count", "insert", "aggregate")),
        DirectiveSpec("sort", "sort document for find", listOf("{ \"_id\": -1 }")),
        DirectiveSpec("limit", "max documents shown", listOf("5", "20", "100")),
        DirectiveSpec("capture", "name = \$.path from the first document"),
        DirectiveSpec("collection", "collection, when the block name is a connection name"),
    )
    override val connectOptions = listOf(
        DirectiveSpec("uri", "mongodb://host:port/db", listOf("mongodb://localhost:27017/demo")),
        DirectiveSpec("db", "database, when the uri has none"),
        DirectiveSpec("timeout", "server selection timeout", listOf("3s")),
    )

    override fun connect(decl: ConnectDecl, ctx: ConnectContext): Connection {
        val uri = decl.option("uri") ?: throw StepError("mongo connection needs uri=mongodb://host:27017/db", "# @connect mongo local uri=mongodb://localhost:27017/demo")
        val cs = try { ConnectionString(uri) } catch (e: Exception) { throw StepError("bad mongo uri: ${e.message?.lineSequence()?.first()}") }
        val db = decl.option("db") ?: cs.database ?: throw StepError("no database in the uri", "…:27017/demo, or db=demo")
        return MongoConnection(decl, cs, db, Durations.parse(decl.option("timeout"), 3_000))
    }

    override fun step(req: StepRequest, conn: Connection): Step {
        val collection = req.target ?: req.directive("collection") ?: throw StepError("no collection", "### mongo <collection>, or # @collection <name>")
        val op = (req.directive("op") ?: "find").lowercase()
        if (op !in setOf("find", "count", "insert", "aggregate")) throw StepError("unknown op '$op'", "# @op find | count | insert | aggregate")
        val limit = req.directive("limit")?.toIntOrNull() ?: DEFAULT_LIMIT
        val body = req.body.trim().ifEmpty { "{}" }
        val sort = req.directive("sort")?.let { parseDoc(it, "@sort") }
        return MongoStep(conn as MongoConnection, collection, op, body, limit, sort, CaptureSpec.parse(req.directives("capture")))
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        val PRETTY: JsonWriterSettings = JsonWriterSettings.builder().indent(true).outputMode(JsonMode.RELAXED).build()

        fun parseDoc(json: String, what: String): Document = try {
            Document.parse(json)
        } catch (e: JsonParseException) {
            throw StepError("$what is not a JSON document: ${e.message?.lineSequence()?.first()}")
        }
    }
}

class MongoConnection(override val decl: ConnectDecl, private val cs: ConnectionString, val dbName: String, private val timeoutMs: Long) : Connection {
    private var client: MongoClient? = null

    @Synchronized
    fun db(): MongoDatabase {
        val c = client ?: MongoClients.create(
            MongoClientSettings.builder().applyConnectionString(cs)
                .applyToClusterSettings { it.serverSelectionTimeout(timeoutMs, TimeUnit.MILLISECONDS) }
                .applicationName("tellij").build()
        ).also { client = it }
        return c.getDatabase(dbName)
    }

    val where: String get() = cs.hosts.joinToString(",")

    override fun close() {
        runCatching { client?.close() }
        client = null
    }
}

class MongoStep(
    private val conn: MongoConnection,
    private val collection: String,
    private val op: String,
    private val body: String,
    private val limit: Int,
    private val sort: Document?,
    private val captures: List<CaptureSpec>,
) : Step {
    override fun run(ctx: RunContext, out: ResultSink) {
        val t0 = System.currentTimeMillis()
        try {
            val coll = conn.db().getCollection(collection)
            when (op) {
                "find" -> {
                    val docs = coll.find(MongoSource.parseDoc(body, "filter")).let { if (sort != null) it.sort(sort) else it }.limit(limit + 1).into(ArrayList())
                    render(ctx, docs, out, t0, "doc")
                }
                "aggregate" -> {
                    val pipeline = try { BsonArray.parse(body).map { Document.parse(it.asDocument().toJson()) } } catch (e: Exception) { throw StepError("pipeline is not a JSON array of stages: ${e.message?.lineSequence()?.first()}") }
                    val docs = coll.aggregate(pipeline).into(ArrayList())
                    render(ctx, docs, out, t0, "doc")
                }
                "count" -> {
                    val n = coll.countDocuments(MongoSource.parseDoc(body, "filter"))
                    out.appendLine("$n document${if (n == 1L) "" else "s"} match")
                    out.done("$n")
                }
                "insert" -> {
                    val trimmed = body.trimStart()
                    val ids = if (trimmed.startsWith("[")) {
                        val docs = BsonArray.parse(body).map { Document.parse(it.asDocument().toJson()) }
                        coll.insertMany(docs).insertedIds.values.map { it.toString() }
                    } else {
                        listOf(coll.insertOne(MongoSource.parseDoc(body, "document")).insertedId.toString())
                    }
                    out.appendLine("inserted ${ids.size} document${if (ids.size == 1) "" else "s"}: ${ids.joinToString()}")
                    out.done("inserted ${ids.size}")
                }
            }
        } catch (e: MongoTimeoutException) {
            throw StepError("cannot reach ${conn.where}: ${e.message?.lineSequence()?.first()?.take(120)}", "is MongoDB up? demo/up.sh mongo")
        } catch (e: MongoException) {
            throw StepError("mongo: ${e.message?.lineSequence()?.first()}", "code ${e.code}")
        }
    }

    private fun render(ctx: RunContext, docs: List<Document>, out: ResultSink, t0: Long, noun: String) {
        val more = if (docs.size > limit) 1 else 0
        val shown = docs.take(limit)
        shown.forEachIndexed { i, d ->
            if (i > 0) out.appendLine("")
            out.appendText(d.toJson(MongoSource.PRETTY))
        }
        if (more > 0) out.appendLine("… more ${noun}s (limit $limit, # @limit N to raise)")
        out.appendLine("(${shown.size} $noun${if (shown.size == 1) "" else "s"} · ${System.currentTimeMillis() - t0} ms)")
        for (c in captures) {
            val first = shown.firstOrNull() ?: throw StepError("capture '${c.name}': no documents")
            ctx.capture(c.name, JsonPathLite.get(first.toJson(JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()), c.expr))
        }
        out.done("${shown.size}${if (more > 0) "+" else ""} $noun${if (shown.size == 1 && more == 0) "" else "s"}")
    }
}
