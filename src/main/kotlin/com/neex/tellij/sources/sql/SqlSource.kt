package com.neex.tellij.sources.sql

import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.render.TableRenderer
import com.neex.tellij.spi.CaptureSpec
import com.neex.tellij.spi.ConnectContext
import com.neex.tellij.spi.Connection
import com.neex.tellij.spi.Durations
import com.neex.tellij.spi.ResultSink
import com.neex.tellij.spi.RunContext
import com.neex.tellij.spi.SourceKind
import com.neex.tellij.spi.Step
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties

/**
 * `### sql <connection>` runs the block body as one statement. The relational half of the DB
 * category; Mongo is the document half. Only JDBC URLs pgjdbc understands are accepted — the
 * driver is instantiated directly because `DriverManager` cannot see drivers living inside a
 * plugin classloader.
 */
class SqlSource : SourceKind {
    override val id: String = "sql"

    override fun connect(decl: ConnectDecl, ctx: ConnectContext): Connection {
        val url = decl.option("url") ?: throw StepError("sql connection needs url=jdbc:postgresql://host:port/db", "# @connect sql pg url=… user=… password=…")
        if (!url.startsWith("jdbc:postgresql:")) throw StepError("only jdbc:postgresql: URLs are supported in this build", "Oracle/MySQL/… is where your org plugs in")
        return PostgresConnection(decl, url, decl.option("user"), decl.option("password"), Durations.parse(decl.option("timeout"), 5_000))
    }

    override fun step(req: StepRequest, conn: Connection): Step {
        val sql = req.body.trim().trimEnd(';')
        if (sql.isBlank()) throw StepError("empty statement", "put the SQL under the ### sql line")
        val limit = req.directive("limit")?.toIntOrNull() ?: DEFAULT_LIMIT
        val timeoutMs = Durations.parse(req.directive("timeout"), 30_000)
        return SqlStep(conn as PostgresConnection, sql, limit, timeoutMs, CaptureSpec.parse(req.directives("capture")))
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

class PostgresConnection(
    override val decl: ConnectDecl,
    val url: String,
    private val user: String?,
    private val password: String?,
    private val connectTimeoutMs: Long,
) : Connection {
    private var jdbc: java.sql.Connection? = null

    @Synchronized
    fun jdbc(): java.sql.Connection {
        jdbc?.takeIf { !it.isClosed }?.let { return it }
        val props = Properties()
        user?.let { props["user"] = it }
        password?.let { props["password"] = it }
        props["connectTimeout"] = (connectTimeoutMs / 1000).coerceAtLeast(1).toString()
        props["ApplicationName"] = "tellij"
        val c = try {
            org.postgresql.Driver().connect(url, props)
        } catch (e: SQLException) {
            throw StepError("cannot connect: ${e.message?.lineSequence()?.first()}", "is the database up? demo/up.sh starts one")
        } ?: throw StepError("driver rejected $url")
        jdbc = c
        return c
    }

    override fun close() {
        runCatching { jdbc?.close() }
        jdbc = null
    }
}

class SqlStep(private val conn: PostgresConnection, private val sql: String, private val limit: Int, private val timeoutMs: Long, private val captures: List<CaptureSpec> = emptyList()) : Step {
    override fun run(ctx: RunContext, out: ResultSink) {
        val c = conn.jdbc()
        val t0 = System.currentTimeMillis()
        c.createStatement().use { st ->
            st.queryTimeout = (timeoutMs / 1000).coerceAtLeast(1).toInt()
            st.maxRows = limit + 1 // one extra row tells us whether there is more
            val hasResultSet = try {
                st.execute(sql)
            } catch (e: SQLException) {
                throw StepError(e.message?.lineSequence()?.first() ?: "SQL error", e.sqlState?.let { "SQLSTATE $it" })
            }
            if (!hasResultSet) {
                out.appendLine(TableRenderer.rowsAffected(st.updateCount))
                out.done("${st.updateCount} affected")
                return
            }
            st.resultSet.use { rs ->
                val (columns, rows) = read(rs, limit)
                val more = if (rows.size > limit) 1 else 0
                val shown = rows.take(limit)
                out.appendText(TableRenderer.render(columns, shown, truncatedRows = more, limit = limit))
                val elapsed = System.currentTimeMillis() - t0
                out.appendLine(TableRenderer.rowCount(shown.size, elapsed) + if (more > 0) " (more available)" else "")
                // `# @capture name = column` takes the first row's value.
                for (c in captures) {
                    val col = columns.indexOfFirst { it.equals(c.expr, ignoreCase = true) }
                    if (col < 0) throw StepError("capture '${c.name}': no column '${c.expr}'", "columns: ${columns.joinToString()}")
                    val row = shown.firstOrNull() ?: throw StepError("capture '${c.name}': the query returned no rows")
                    ctx.capture(c.name, row[col] ?: "NULL")
                }
                out.done("${shown.size}${if (more > 0) "+" else ""} row${if (shown.size == 1 && more == 0) "" else "s"}")
            }
        }
    }

    private fun read(rs: ResultSet, limit: Int): Pair<List<String>, List<List<String?>>> {
        val md = rs.metaData
        val columns = (1..md.columnCount).map { md.getColumnLabel(it) }
        val rows = ArrayList<List<String?>>()
        while (rs.next() && rows.size <= limit) {
            rows += (1..md.columnCount).map { i -> rs.getObject(i)?.let { v -> if (v is ByteArray) "bytea(${v.size})" else rs.getString(i) } }
        }
        return columns to rows
    }
}
