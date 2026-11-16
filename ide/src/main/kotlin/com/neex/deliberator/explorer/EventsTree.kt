package com.neex.deliberator.explorer

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** What the nodes of the EventBridge subtree hold: a stable [key] (the subtree is reconciled by it, so a refresh or a search keeps expansion and selection). */
internal interface EvKey { val key: String }
internal data class EvRoot(val title: String) : EvKey { override val key get() = "eventbridge"; override fun toString() = title }
internal data class EvBus(val bus: BusRow) : EvKey { override val key get() = "b:${bus.name}"; override fun toString() = bus.name }
internal data class EvRule(val rule: RuleRow) : EvKey { override val key get() = "r:${rule.key}"; override fun toString() = rule.name }
internal data class EvTarget(val rule: RuleRow, val target: TargetRow) : EvKey { override val key get() = "t:${rule.key}/${target.id}"; override fun toString() = target.name }
internal data class EvNote(val text: String) : EvKey { override val key get() = "note"; override fun toString() = text }

/**
 * The EventBridge node of the AWS tab: bus -> rule -> target, from `events.map` (the cached map at once with its age, `events.map` again on Refresh), filtered by the
 * search field over the bus, the rule, its pattern text and its targets. The subtree is reconciled IN PLACE: nodes with the same key stay (and keep their expansion and
 * selection), only what appeared or went is inserted or removed, a node whose data changed is repainted. Every daemon call is off the EDT.
 */
internal class EventsTree(private val project: Project, private val model: DefaultTreeModel, private val tree: JTree) {
    val node = DefaultMutableTreeNode(EvRoot("EventBridge"))

    @Volatile var map: EventsMap? = null; private set
    @Volatile var query: String = ""; private set
    @Volatile private var seq = 0
    @Volatile var message: String = ""; private set
    @Volatile var lastError: String? = null; private set

    private class Want(val obj: EvKey, val children: List<Want> = emptyList())

    private fun wanted(m: EventsMap?, q: String): List<Want> {
        if (m == null) return listOf(Want(EvNote(message.ifBlank { "no map yet: Refresh reads EventBridge (read calls only)" })))
        val buses = m.filtered(q)
        if (buses.isEmpty()) return listOf(Want(EvNote(if (q.isBlank()) "no event bus found" else "nothing matches \"$q\"")))
        return buses.map { b -> Want(EvBus(b), b.rules.map { r -> Want(EvRule(r), r.targets.map { t -> Want(EvTarget(r, t)) }) }) }
    }

    /** Shows [m] filtered by [q]: reconciles the subtree. EDT. */
    fun show(m: EventsMap?, q: String = query) {
        map = m; query = q
        val title = "EventBridge" + (m?.let { " (${it.rules} rule(s)" + (if (q.isNotBlank()) ", showing ${it.filtered(q).sumOf { b -> b.rules.size }}" else "") + ")" } ?: "")
        if ((node.userObject as EvRoot).title != title) { node.userObject = EvRoot(title); if (node.parent != null) model.nodeChanged(node) }
        val fresh = mutableListOf<DefaultMutableTreeNode>()
        sync(node, wanted(m, q), fresh)
        if (node.parent != null) {
            // buses and rules that appeared open (a search shows its hits); the ones the person collapsed stay as they are
            fresh.filter { it.userObject !is EvTarget && it.childCount > 0 }.forEach { tree.expandPath(TreePath(it.path)) }
            // a search shows its hits with their targets
            if (q.isNotBlank()) { tree.expandPath(TreePath(node.path)); allRules().forEach { tree.expandPath(TreePath(it.path)) } }
        }
    }

    private fun sync(parent: DefaultMutableTreeNode, want: List<Want>, fresh: MutableList<DefaultMutableTreeNode>) {
        val attached = parent.parent != null || parent === node
        val keys = want.map { it.obj.key }.toSet()
        for (i in (0 until parent.childCount).reversed()) {
            val n = parent.getChildAt(i) as DefaultMutableTreeNode
            if ((n.userObject as EvKey).key !in keys) { parent.remove(i); if (attached) model.nodesWereRemoved(parent, intArrayOf(i), arrayOf(n)) }
        }
        want.forEachIndexed { i, w ->
            val existing = (0 until parent.childCount).map { parent.getChildAt(it) as DefaultMutableTreeNode }.firstOrNull { (it.userObject as EvKey).key == w.obj.key }
            val n = if (existing == null) {
                DefaultMutableTreeNode(w.obj).also { parent.insert(it, minOf(i, parent.childCount)); fresh += it; if (attached) model.nodesWereInserted(parent, intArrayOf(parent.getIndex(it))) }
            } else {
                if (parent.getIndex(existing) != i) { // a different order (a rule renamed around it): move, rare
                    parent.remove(existing); parent.insert(existing, minOf(i, parent.childCount)); if (attached) model.nodeStructureChanged(parent)
                }
                if (existing.userObject != w.obj) { existing.userObject = w.obj; if (attached) model.nodeChanged(existing) }
                existing
            }
            sync(n, w.children, fresh)
        }
    }

    private fun allRules(): List<DefaultMutableTreeNode> = node.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.filter { it.userObject is EvRule || it.userObject is EvBus }

    /** The search field changed. EDT. */
    fun search(q: String) { if (q != query) show(map, q) }

    /** Shows the cached map at once, then reads EventBridge again and reconciles to it (both off the EDT, the tree updated on it). */
    fun load(env: String, fetch: Boolean = true) {
        val mine = ++seq
        ApplicationManager.getApplication().executeOnPooledThread {
            val svc = WorkspaceService.getInstance(project)
            try {
                val cached = EventsOps.cachedMap(svc, env)
                if (cached != null) ApplicationManager.getApplication().invokeLater({ if (seq == mine) show(cached) }, ModalityState.any())
                if (fetch) {
                    val fresh = EventsOps.fetchMap(svc, env)
                    ApplicationManager.getApplication().invokeLater({ if (seq == mine) show(fresh) }, ModalityState.any())
                } else if (cached == null) ApplicationManager.getApplication().invokeLater({ if (seq == mine) { message = ""; show(null) } }, ModalityState.any())
            } catch (e: DaemonException) {
                lastError = e.message
                ApplicationManager.getApplication().invokeLater({ if (seq == mine) { message = "$env: ${e.message?.take(160)}"; if (map == null || map?.env != env) show(null) } }, ModalityState.any())
            }
        }
    }

    /** Paints a node of the subtree; false for any other node. */
    fun paint(c: ColoredTreeCellRenderer, u: Any?): Boolean {
        when (u) {
            is EvRoot -> c.append(u.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            is EvBus -> { c.append(u.bus.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES); c.append("   bus, ${u.bus.rules.size} rule(s)", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            is EvRule -> {
                c.append(u.rule.name, if (u.rule.listening) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                c.append("   ${u.rule.state}", if (u.rule.listening) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES)
                if (!u.rule.listening) c.append(" (not listening)", SimpleTextAttributes.ERROR_ATTRIBUTES)
                if (u.rule.patternLine.isNotBlank()) c.append("   ${u.rule.patternLine}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is EvTarget -> { c.append("-> ${u.target.kind} ", SimpleTextAttributes.GRAYED_ATTRIBUTES); c.append(u.target.name); u.target.input?.let { c.append("   input $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }; u.target.dlq?.let { c.append("   dead-letter ${it.substringAfterLast(':')}", SimpleTextAttributes.GRAYED_ATTRIBUTES) } }
            is EvNote -> c.append(u.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            else -> return false
        }
        return true
    }

    /** The lines of the subtree as the tab shows them (tests). */
    fun lines(): List<String> = buildList {
        fun walk(n: DefaultMutableTreeNode, depth: Int) {
            val u = n.userObject
            add("  ".repeat(depth) + when (u) {
                is EvBus -> u.bus.name
                is EvRule -> "${u.rule.name} ${u.rule.state} ${u.rule.patternLine}".trim()
                is EvTarget -> "-> ${u.target.kind} ${u.target.name}"
                else -> u.toString()
            })
            for (i in 0 until n.childCount) walk(n.getChildAt(i) as DefaultMutableTreeNode, depth + 1)
        }
        walk(node, 0)
    }

    companion object {
        val RULE: DataKey<RuleRow> = DataKey.create("deliberator.events.rule")
        val TARGET: DataKey<TargetRow> = DataKey.create("deliberator.events.target")
        val CONSOLE: DataKey<ConsoleThing> = DataKey.create("deliberator.console.thing")
    }
}

/** What Open in AWS Console acts on: the console kind and name of the explorer row (`console.url`'s words). */
data class ConsoleThing(val kind: String, val name: String)
