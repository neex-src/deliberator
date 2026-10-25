package com.neex.deliberator.explorer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.psi.PsiDocumentManager
import com.neex.deliberator.run.PrettyJson
import com.neex.deliberator.run.ResponsePeek
import com.neex.deliberator.run.RunSnapshot
import com.neex.deliberator.run.RunView
import com.neex.deliberator.run.ScnRunListener
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.scn.AwsResources
import com.neex.deliberator.scn.LiveResource
import com.neex.deliberator.scn.ResponsePaths
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The "AWS" tab: what exists in the project's environment (queues, tables, buckets, topics, parameters, ...), from `aws.discover`.
 * Double-click or Enter on a resource writes the step that reads it into `explore-<env>.scn` of the project, runs it and shows the
 * response in the peek: browsing the emulator without a console tab, and what you opened stays as a scenario you can edit.
 */
class AwsExplorerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val root = DefaultMutableTreeNode("environment")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val status = JBLabel(" ")
    private val envBox = JComboBox<String>()
    private var loadingEnvs = false
    @Volatile private var drift: Drift? = null

    internal class KindNode(val kind: String, val title: String, val count: Int) { override fun toString() = "$title ($count)" }
    internal class ResNode(val kind: String, val r: LiveResource, val undeclared: Boolean = false) { override fun toString() = r.name }
    internal class MissingNode(val name: String, val address: String?) { override fun toString() = name }

    init {
        tree.isRootVisible = false
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: javax.swing.JTree, value: Any?, sel: Boolean, exp: Boolean, leaf: Boolean, row: Int, focus: Boolean) {
                when (val u = (value as? DefaultMutableTreeNode)?.userObject) {
                    is KindNode -> append(u.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    is ResNode -> {
                        append(u.r.name); u.r.ref?.takeIf { u.kind == "ssm" }?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                        if (u.undeclared) append("   not in Terraform", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    }
                    is MissingNode -> { append(u.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, java.awt.Color.GRAY)); append("   declared, not in the environment" + (u.address?.let { "  ($it)" } ?: ""), SimpleTextAttributes.ERROR_ATTRIBUTES) }
                    else -> append(u.toString())
                }
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) open() }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) open() }
        })
        val refresh = JButton("Refresh").apply { addActionListener { refresh() } }
        val driftButton = JButton("Terraform drift…").apply { toolTipText = "Compare a Terraform root (terraform show -json, read only) with this environment"; addActionListener { drift() } }
        val planButton = JButton("Plan").apply { toolTipText = "terraform plan of the chosen root onto this (local) environment"; addActionListener { terraform("plan") } }
        val applyButton = JButton("Apply").apply { toolTipText = "terraform apply of the chosen root onto this (local) environment; refused for a remote one"; addActionListener { terraform("apply") } }
        envBox.addActionListener { if (!loadingEnvs) (envBox.selectedItem as? String)?.let { pick -> WorkspaceService.getInstance(project).selectEnv(pick.substringBefore("  ")); refresh() } }
        add(JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(envBox, BorderLayout.WEST); add(status, BorderLayout.CENTER) }, BorderLayout.CENTER)
            add(JPanel().apply { add(driftButton); add(planButton); add(applyButton); add(refresh) }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        refresh()
    }

    private fun env() = WorkspaceService.getInstance(project).selectedEnv

    private fun fillEnvs() {
        val m = WorkspaceService.getInstance(project).model
        val names = m.envs.map { if (it.kind == "remote") "${it.name}  (remote)" else it.name }.ifEmpty { listOf(env()) }
        loadingEnvs = true
        try {
            envBox.removeAllItems(); names.forEach(envBox::addItem)
            envBox.selectedItem = names.firstOrNull { it.substringBefore("  ") == env() }
        } finally { loadingEnvs = false }
    }

    /** Asks for a Terraform root (remembered per project), compares it with the environment, annotates the tree and writes `explore/drift-<env>.txt`. */
    private fun drift() {
        val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        val last = props.getValue("deliberator.tf.dir")
        val vf = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Terraform Root to Compare"),
            project, last?.let { LocalFileSystem.getInstance().findFileByPath(it) }) ?: return
        props.setValue("deliberator.tf.dir", vf.path)
        runDrift(vf.path)
    }

    private fun chooseRoot(): String? {
        val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        val vf = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Terraform Root"),
            project, props.getValue("deliberator.tf.dir")?.let { LocalFileSystem.getInstance().findFileByPath(it) }) ?: return null
        props.setValue("deliberator.tf.dir", vf.path)
        return vf.path
    }

    /** `terraform.local` plan/apply of a root onto the selected local environment; the output is a file in the project (`explore/terraform-<action>-<env>.txt`), the tree and drift refresh after. */
    fun terraform(action: String, dir: String? = null) {
        val root = dir ?: chooseRoot() ?: return
        if (action == "apply") {
            val vars = try {
                TerraformVars.parse(WorkspaceService.getInstance(project).call("terraform.local", com.google.gson.JsonObject().apply { addProperty("env", env()); addProperty("dir", root); addProperty("action", "vars") }).result!!.asJsonObject)
            } catch (e: Exception) { Ui.notify(project, "Terraform", e.message ?: e.toString(), com.intellij.notification.NotificationType.ERROR); return }
            if (com.intellij.openapi.ui.Messages.showYesNoDialog(project, TerraformVars.confirmText(root, env(), vars), "Apply Terraform", "Apply", "Cancel", com.intellij.openapi.ui.Messages.getQuestionIcon()) != com.intellij.openapi.ui.Messages.YES) return
        }
        status.text = "terraform $action ..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val a = com.google.gson.JsonObject().apply { addProperty("env", env()); addProperty("dir", root); addProperty("action", action) }
                var n = 0
                val t = WorkspaceService.getInstance(project).call("terraform.local", a) { ev -> if (ev.name == "tf.line" && ++n % 20 == 0) ApplicationManager.getApplication().invokeLater({ status.text = "terraform $action: ${ev.json.get("line")?.asString?.take(80)}" }, ModalityState.any()) }
                val r = t.result!!.asJsonObject
                val text = r.get("output").asString
                val summary = r.get("summary")?.asString.orEmpty()
                ApplicationManager.getApplication().invokeLater({
                    status.text = "terraform $action: $summary"
                    writeText("terraform-$action-${env()}.txt", "# terraform $action of $root on ${env()}\n# ${summary}\n\n$text\n")
                    AwsResources.getInstance().invalidate()
                    if (action == "apply") { runDrift(root) } else refresh()
                }, ModalityState.nonModal())
            } catch (e: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(AwsExplorerPanel::class.java).warn("terraform $action failed", e)
                ApplicationManager.getApplication().invokeLater({ status.text = "terraform failed"; Ui.notify(project, "Terraform", e.message ?: e.toString(), com.intellij.notification.NotificationType.ERROR) }, ModalityState.any())
            }
        }
    }

    private fun writeText(name: String, content: String) {
        val base = project.basePath ?: return
        val vf = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile?, Exception> {
            val dir = VfsUtil.createDirectoryIfMissing(Path.of(base, "explore").toString()) ?: return@compute null
            (dir.findChild(name) ?: dir.createChildData(this, name)).also { VfsUtil.saveText(it, content) }
        } ?: return
        Ui.openEditor(project, vf)
    }

    fun runDrift(dir: String) {
        status.text = "comparing $dir with ${env()} ..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val r = WorkspaceService.getInstance(project).call("terraform.drift",
                    com.google.gson.JsonObject().apply { addProperty("env", env()); addProperty("dir", dir) }).result!!.asJsonObject
                val d = Drift.parse(r)
                drift = d
                ApplicationManager.getApplication().invokeLater({
                    render(AwsResources.getInstance().get(project))
                    status.text = "${env()}: ${d.matching}/${d.declared} declared present, ${d.missing.size} missing, ${d.undeclared.size} undeclared"
                }, ModalityState.any())
                ApplicationManager.getApplication().invokeLater({ writeReport(d) }, ModalityState.nonModal())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({ status.text = "drift failed"; Ui.notify(project, "Terraform drift", e.message ?: e.toString(), com.intellij.notification.NotificationType.ERROR) }, ModalityState.any())
            }
        }
    }

    private fun writeReport(d: Drift) = writeText("drift-${env()}.txt", d.text())

    fun refresh() {
        status.text = "reading ${env()} ..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val svc = com.neex.deliberator.scn.ScnRegistry.getInstance()
            if (svc.knownClient() == null) runCatching { svc.refresh() }
            if (WorkspaceService.getInstance(project).model.envs.isEmpty()) runCatching { WorkspaceService.getInstance(project).refreshNow() }
            val res = AwsResources.getInstance().get(project, ttlMs = 0)
            ApplicationManager.getApplication().invokeLater({ render(res) }, ModalityState.any())
        }
    }

    fun render(res: Map<String, List<LiveResource>>) {
        fillEnvs()
        root.removeAllChildren()
        var total = 0
        val dr = drift?.takeIf { it.env == env() }
        for (k in ExplorerSteps.KINDS) {
            val items = res[k.kind].orEmpty()
            if (items.isEmpty()) continue
            total += items.size
            DefaultMutableTreeNode(KindNode(k.kind, k.title, items.size)).also { n ->
                items.forEach { n.add(DefaultMutableTreeNode(ResNode(k.kind, it, dr?.isUndeclared(k.kind, it.name) == true))) }
                dr?.missing?.filter { it.kind == k.kind }?.forEach { n.add(DefaultMutableTreeNode(MissingNode(it.name, it.address))) }
                root.add(n)
            }
        }
        model.reload()
        for (i in 0 until tree.rowCount) tree.expandRow(i)
        if (dr == null) status.text = if (total == 0) "${env()}: nothing found (is the runtime up?)" else "${env()}: $total resources  ·  double-click opens one as a step"
    }

    /** Text of the tree as lines `kind: name` (for tests); drift marks as ` [undeclared]` / ` [missing]`. */
    fun lines(): List<String> = (0 until root.childCount).flatMap { k ->
        val kn = root.getChildAt(k) as DefaultMutableTreeNode
        val kind = (kn.userObject as KindNode).kind
        (0 until kn.childCount).map {
            when (val u = (kn.getChildAt(it) as DefaultMutableTreeNode).userObject) {
                is ResNode -> "$kind: ${u.r.name}" + if (u.undeclared) " [undeclared]" else ""
                is MissingNode -> "$kind: ${u.name} [missing]"
                else -> "$kind: $u"
            }
        }
    }

    private fun open() {
        val n = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ResNode ?: return
        val step = ExplorerSteps.step(n.kind, n.r) ?: return
        openStep(project, env(), step)
    }

    override fun dispose() {}

    companion object {
        /** Appends [step] to `explore-<env>.scn` (created with a header), opens it, runs that step and peeks at the response when it ends. EDT. */
        fun openStep(project: Project, env: String, step: String) {
            val base = project.basePath ?: return
            val name = ExplorerSteps.fileName(env)
            val vf = WriteAction.compute<com.intellij.openapi.vfs.VirtualFile?, Exception> {
                val dir = VfsUtil.createDirectoryIfMissing(Path.of(base, "explore").toString()) ?: return@compute null
                dir.findChild(name) ?: dir.createChildData(this, name).also { VfsUtil.saveText(it, ExplorerSteps.header(env)) }
            } ?: return
            val editor = Ui.openEditor(project, vf) ?: return
            val d = editor.document
            val before = (PsiDocumentManager.getInstance(project).also { it.commitDocument(d) }.getPsiFile(d) as? ScnFile)?.steps?.size ?: 0
            val text = (if (d.textLength > 0 && !d.text.endsWith("\n")) "\n" else "") + "\n" + step
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { d.insertString(d.textLength, text) }
            FileDocumentManager.getInstance().saveDocument(d)
            PsiDocumentManager.getInstance(project).commitDocument(d)
            editor.caretModel.moveToOffset(d.textLength - 1)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
            val index = before + 1
            val conn = project.messageBus.connect()
            conn.subscribe(ScnRunListener.TOPIC, object : ScnRunListener {
                override fun runFinished(path: String, snapshot: RunSnapshot) {
                    if (path != vf.path) return
                    conn.disconnect()
                    val resp = ResponsePaths.response(snapshot.sections[index])
                    ApplicationManager.getApplication().invokeLater({
                        val psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? ScnFile
                        val s = psi?.steps?.getOrNull(index - 1)
                        if (resp != null && s != null) ResponsePeek.show(editor, s, PrettyJson.render(resp))
                    }, ModalityState.nonModal())
                }
            })
            ScnRunner.getInstance(project).run(vf, listOf(index))
        }
    }
}
