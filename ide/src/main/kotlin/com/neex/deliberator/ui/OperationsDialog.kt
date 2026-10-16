package com.neex.deliberator.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.ArgSpec
import com.neex.deliberator.workspace.OpSpec
import com.neex.deliberator.workspace.OperationForm
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Deliberator | Operations...: every op of `op.describe` with a form generated from its arguments
 * ([OperationForm]); `ws` is the project's and is not shown. Events and the terminal line stream into
 * the text pane, which can be opened as an editor tab. Closing the dialog cancels the request in flight.
 */
class OperationsDialog(private val project: Project, private val svc: WorkspaceService) : DialogWrapper(project, false) {
    private var all: List<OpSpec> = emptyList()
    private val listModel = DefaultListModel<OpSpec>()
    private val list = JBList(listModel)
    private val filter = SearchTextField(false)
    private val doc = JBLabel()
    private val formHost = JPanel(BorderLayout())
    private val output = JBTextArea().apply { isEditable = false; font = JBUI.Fonts.create("Monospaced", 12) }
    private val fields = linkedMapOf<String, () -> String>()
    private var current: OpSpec? = null
    @Volatile private var call: DaemonClient.Call? = null
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val runAction = object : AbstractAction("Run") {
        override fun actionPerformed(e: ActionEvent) = run()
    }
    private val editorAction = object : AbstractAction("Open in editor") {
        override fun actionPerformed(e: ActionEvent) {
            val name = (current?.op ?: "operation") + ".txt"
            FileEditorManager.getInstance(project).openFile(LightVirtualFile(name, PlainTextFileType.INSTANCE, output.text), true)
        }
    }

    init {
        title = "Deliberator Operations (workspace ${svc.ws})"
        init()
        list.cellRenderer = com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { (if (it.mutating) "* " else "  ") + it.op }
        list.addListSelectionListener { if (!it.valueIsAdjusting) list.selectedValue?.let { s -> select(s) } }
        filter.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = applyFilter()
        })
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val specs = OperationForm.load(svc.client())
                ApplicationManager.getApplication().invokeLater({ all = specs; applyFilter() }, com.intellij.openapi.application.ModalityState.any())
            } catch (e: DaemonException) {
                ApplicationManager.getApplication().invokeLater({ output.text = "op.describe failed: ${e.message}" }, com.intellij.openapi.application.ModalityState.any())
            }
        }
    }

    private fun applyFilter() {
        val q = filter.text.trim().lowercase()
        listModel.clear()
        all.filter { q.isEmpty() || it.op.contains(q) || (it.doc ?: "").lowercase().contains(q) }.forEach { listModel.addElement(it) }
        if (listModel.size() > 0 && list.selectedIndex < 0) list.selectedIndex = 0
    }

    private fun select(s: OpSpec) {
        current = s
        doc.text = "<html><b>${s.op}</b> (${if (s.mutating) "changes state" else "read"}) &mdash; ${s.doc ?: ""}" +
            (if (s.events.isNotEmpty()) "<br>events: ${s.events.joinToString(", ")}" else "") + "</html>"
        fields.clear()
        val form = JPanel(GridBagLayout())
        var row = 0
        for (a in s.args) {
            val c = GridBagConstraints().apply { gridy = row; anchor = GridBagConstraints.WEST; insets = JBUI.insets(2, 4) }
            form.add(JBLabel(a.name + (if (a.required) " *" else "")).apply { toolTipText = tip(a) }, c.apply { gridx = 0 })
            val init = OperationForm.initialText(a)
            if (a.type == "bool") {
                val cb = JBCheckBox("", init == "true")
                fields[a.name] = { if (cb.isSelected || init.isNotEmpty()) cb.isSelected.toString() else "" }
                form.add(cb, c.apply { gridx = 1 })
            } else {
                val tf = JBTextField(init, 28).apply { toolTipText = tip(a) }
                fields[a.name] = { tf.text }
                form.add(tf, c.apply { gridx = 1; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 })
            }
            form.add(JBLabel("<html><small>${a.type}${a.doc?.let { " &mdash; $it" } ?: ""}</small></html>"), c.apply { gridx = 2; fill = GridBagConstraints.NONE; weightx = 0.0 })
            row++
        }
        formHost.removeAll()
        formHost.add(form, BorderLayout.NORTH)
        formHost.revalidate(); formHost.repaint()
    }

    private fun tip(a: ArgSpec) = "${a.type}${if (a.required) ", required" else ""}${a.doc?.let { ": $it" } ?: ""}"

    private fun run() {
        val s = current ?: return
        val r = OperationForm.build(s, fields.mapValues { it.value() }, svc.ws)
        if (r.args == null) { output.text = "Not sent:\n" + r.problems.joinToString("\n") { "  - $it" }; return }
        output.text = "> ${s.op} ${r.args}\n"
        runAction.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = svc.client()
                val c = client.start(s.op, r.args, true) { ev -> append("event ${ev.name}: ${ev.json}\n") }
                call = c
                val t = c.await()
                append(if (t.ok) pretty(t.raw) else "ERROR ${t.errorType}: ${t.errorMessage}\n${pretty(t.raw)}")
            } catch (e: DaemonException) {
                append("failed: ${e.type}: ${e.message}\n")
            } catch (e: java.util.concurrent.CancellationException) {
                append("cancelled\n")
            } finally {
                call = null
                ApplicationManager.getApplication().invokeLater({ runAction.isEnabled = true }, com.intellij.openapi.application.ModalityState.any())
            }
        }
    }

    private fun pretty(raw: String) = try { gson.toJson(JsonParser.parseString(raw)) + "\n" } catch (_: Exception) { raw + "\n" }

    private fun append(text: String) =
        ApplicationManager.getApplication().invokeLater({ output.append(text) }, com.intellij.openapi.application.ModalityState.any())

    override fun createActions(): Array<Action> = arrayOf(runAction, editorAction, cancelAction.also { it.putValue(Action.NAME, "Close") })

    override fun doCancelAction() {
        call?.cancel()
        super.doCancelAction()
    }

    override fun dispose() {
        call?.cancel()
        super.dispose()
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout()).apply {
            add(filter, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(240, 480)
        }
        val right = JPanel(BorderLayout()).apply {
            val top = JPanel(BorderLayout()).apply { add(doc, BorderLayout.NORTH); add(formHost, BorderLayout.CENTER) }
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(output), BorderLayout.CENTER)
            preferredSize = Dimension(720, 480)
        }
        return JPanel(BorderLayout(8, 0)).apply { add(left, BorderLayout.WEST); add(right, BorderLayout.CENTER) }
    }

    override fun getPreferredFocusedComponent(): JComponent = filter
}
