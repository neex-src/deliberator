package com.neex.deliberator.explorer

import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.run.PrettyJson
import com.neex.deliberator.run.ResponsePeek
import com.neex.deliberator.run.RunSnapshot
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
 * Double-click or Enter on a resource writes the step that reads it into `explore-<env>.scn` of the workspace's project, runs it and
 * shows the response in the peek: browsing the emulator without a console tab, and what you opened stays as a scenario you can edit.
 * Every file the tab produces (the Terraform transcript, the drift report, the scenario) is written by the daemon (`explore.write`,
 * which names the place); the tab opens the path it gets. Every daemon call is off the EDT.
 */
class AwsExplorerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, com.intellij.openapi.actionSystem.UiDataProvider {
    private val root = DefaultMutableTreeNode("environment")
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)
    private val status = JBLabel(" ")
    private val envBox = JComboBox<String>()
    private var loadingEnvs = false
    @Volatile private var drift: Drift? = null
    /** The Terraform root of the last drift comparison: where a drift row's address is looked up (`terraform.locate`). */
    @Volatile private var driftRoot: String? = null

    /** The last failure of a button (the text of its balloon), for tests. */
    @Volatile internal var lastError: String? = null; private set

    /** Asked before an apply (root, environment, the variables the daemon derived); true applies. A dialog, replaced by tests. EDT. */
    @Volatile internal var applyConfirmer: (String, String, Map<String, String>) -> Boolean = { root, env, vars ->
        com.intellij.openapi.ui.Messages.showYesNoDialog(project, TerraformVars.confirmText(root, env, vars), "Apply Terraform", "Apply", "Cancel",
            com.intellij.openapi.ui.Messages.getQuestionIcon()) == com.intellij.openapi.ui.Messages.YES
    }

    internal class KindNode(val kind: String, val title: String, val count: Int) { override fun toString() = "$title ($count)" }
    internal class ResNode(val kind: String, val r: LiveResource, val undeclared: Boolean = false, val link: ServiceLink? = null) { override fun toString() = r.name }
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
                        u.link?.let { append("   -> ${it.text()}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    }
                    is MissingNode -> { append(u.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, java.awt.Color.GRAY)); append("   declared, not in the environment" + (u.address?.let { "  ($it)" } ?: ""), SimpleTextAttributes.ERROR_ATTRIBUTES) }
                    else -> append(u.toString())
                }
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) open() }
        })
        com.intellij.ui.PopupHandler.installPopupMenu(tree, com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("deliberator.explorer") as com.intellij.openapi.actionSystem.DefaultActionGroup,
            com.intellij.openapi.actionSystem.ActionPlaces.POPUP)
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

    /** The row's service (M6.4) and, for a drift row, its address: the data of the popup actions. */
    override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
        val u = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
        sink[ServiceLinks.LINK] = (u as? ResNode)?.link
        sink[DRIFT_ADDRESS] = (u as? MissingNode)?.address
        sink[EXPLORER] = this
    }

    /**
     * `terraform.locate` of a drift row's [address] in the root of the last comparison, and the `.tf` file opened with the caret on the declaration (M6.4).
     * A module from a registry has no local source: the daemon answers the `module` block's own line (`module-only`) and says so.
     */
    fun openDeclaration(address: String, inRoot: String? = null) {
        val root = inRoot ?: driftRoot ?: com.intellij.ide.util.PropertiesComponent.getInstance(project).getValue("deliberator.tf.dir")
        if (root == null) { Ui.notify(project, "Terraform", "Compare a Terraform root first (Terraform drift...): its address is looked up there.", NotificationType.WARNING); return }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val r = WorkspaceService.getInstance(project).call("terraform.locate", JsonObject().apply { addProperty("root", root); addProperty("address", address) }).resultObject()
                val file = r.get("file")?.asString ?: throw DaemonException("terraform.locate returned no file", "tf/no-file")
                val line = r.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
                val moduleOnly = r.get("module-only")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                Ui.openFileLater(project, file, line - 1) { ok ->
                    if (!ok) Ui.notify(project, "Terraform", "Cannot open $file", NotificationType.WARNING)
                    else status.text = "$address: ${Path.of(file).fileName}:$line" + if (moduleOnly) "  (the module block: its source is not local)" else ""
                }
            } catch (e: Exception) {
                failed("Terraform locate", "locate", e)
            }
        }
    }

    /** The status line (tests). */
    internal fun statusText(): String = status.text

    private fun fillEnvs() {
        val m = WorkspaceService.getInstance(project).model
        val names = m.envs.map { if (it.kind == "remote") "${it.name}  (remote)" else it.name }.ifEmpty { listOf(env()) }
        loadingEnvs = true
        try {
            envBox.removeAllItems(); names.forEach(envBox::addItem)
            envBox.selectedItem = names.firstOrNull { it.substringBefore("  ") == env() }
        } finally { loadingEnvs = false }
    }

    private fun setStatus(text: String) = ApplicationManager.getApplication().invokeLater({ status.text = text }, ModalityState.any())

    private fun failed(title: String, what: String, e: Exception) {
        com.intellij.openapi.diagnostic.Logger.getInstance(AwsExplorerPanel::class.java).warn("$title failed", e)
        lastError = e.message ?: e.toString()
        setStatus("$what failed")
        Ui.notify(project, title, e.message ?: e.toString(), NotificationType.ERROR)
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

    private fun tfArgs(root: String, action: String) = JsonObject().apply { addProperty("env", env()); addProperty("dir", root); addProperty("action", action) }

    /**
     * `terraform.local` plan/apply of a root onto the selected local environment, all of it on a pooled thread: for an apply the daemon's
     * derived variables are fetched first and the question is put on the EDT ([applyConfirmer]); the transcript becomes
     * `explore/terraform-<action>-<env>.txt` (through the daemon) and opens; the tree and the drift refresh after.
     */
    fun terraform(action: String, dir: String? = null) {
        val root = dir ?: chooseRoot() ?: return
        status.text = "terraform $action ..."
        lastError = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val svc = WorkspaceService.getInstance(project)
            try {
                if (action == "apply") {
                    val vars = TerraformVars.parse(svc.call("terraform.local", tfArgs(root, "vars")).result!!.asJsonObject)
                    var yes = false
                    ApplicationManager.getApplication().invokeAndWait({ yes = applyConfirmer(root, env(), vars) }, ModalityState.nonModal())
                    if (!yes) { setStatus(" "); return@executeOnPooledThread }
                }
                var n = 0
                val t = svc.call("terraform.local", tfArgs(root, action)) { ev -> if (ev.name == "tf.line" && ++n % 20 == 0) setStatus("terraform $action: ${ev.json.get("line")?.asString?.take(80)}") }
                val r = t.result!!.asJsonObject
                val text = r.get("output").asString
                val summary = r.get("summary")?.asString.orEmpty()
                val path = writeText("terraform-$action-${env()}.txt", "# terraform $action of $root on ${env()}\n# ${summary}\n\n$text\n")
                AwsResources.getInstance().invalidate()
                ApplicationManager.getApplication().invokeLater({
                    status.text = "terraform $action: $summary"
                    Ui.openFileLater(project, path)
                    if (action == "apply") runDrift(root) else refresh()
                }, ModalityState.nonModal())
            } catch (e: Exception) {
                failed("Terraform", "terraform", e)
            }
        }
    }

    /** `explore.write`: the daemon writes `<workspace project>/explore/<name>` and answers the path. Blocking, off the EDT. */
    private fun writeText(name: String, content: String): String =
        WorkspaceService.getInstance(project).call("explore.write", JsonObject().apply { addProperty("name", name); addProperty("text", content) })
            .resultObject().get("path")?.asString ?: throw DaemonException("explore.write returned no path", "explore/no-path")

    fun runDrift(dir: String) {
        status.text = "comparing $dir with ${env()} ..."
        lastError = null
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val r = WorkspaceService.getInstance(project).call("terraform.drift",
                    JsonObject().apply { addProperty("env", env()); addProperty("dir", dir) }).result!!.asJsonObject
                val d = Drift.parse(r)
                drift = d
                driftRoot = dir
                val report = writeText("drift-${env()}.txt", d.text())
                // the tree shows what exists now (an apply just changed it, the cache was dropped): fetched here, off the EDT, not read from a cache that is empty
                val res = AwsResources.getInstance().fetchNow(project)
                ApplicationManager.getApplication().invokeLater({
                    render(res)
                    status.text = "${env()}: ${d.matching}/${d.declared} declared present, ${d.missing.size} missing, ${d.undeclared.size} undeclared"
                }, ModalityState.any())
                Ui.openFileLater(project, report)
            } catch (e: Exception) {
                failed("Terraform drift", "drift", e)
            }
        }
    }

    fun refresh() {
        status.text = "reading ${env()} ..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val svc = com.neex.deliberator.scn.ScnRegistry.getInstance()
            if (svc.knownClient() == null) runCatching { svc.refresh() }
            if (WorkspaceService.getInstance(project).model.envs.isEmpty()) runCatching { WorkspaceService.getInstance(project).refreshNow() }
            val res = AwsResources.getInstance().fetchNow(project)
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
                val services = WorkspaceService.getInstance(project).model.services
                items.forEach { n.add(DefaultMutableTreeNode(ResNode(k.kind, it, dr?.isUndeclared(k.kind, it.name) == true, ServiceLinks.forResource(k.kind, it.name, services)))) }
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
                is ResNode -> "$kind: ${u.r.name}" + (if (u.undeclared) " [undeclared]" else "") + (u.link?.let { " -> ${it.text()}" } ?: "")
                is MissingNode -> "$kind: ${u.name} [missing]"
                else -> "$kind: $u"
            }
        }
    }

    private fun open() {
        val sel = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
        if (sel is MissingNode) { sel.address?.let(::openDeclaration); return }
        val n = sel as? ResNode ?: return
        val step = ExplorerSteps.step(n.kind, n.r) ?: return
        openStep(env(), step)
    }

    override fun dispose() {}

    companion object {
        val DRIFT_ADDRESS: com.intellij.openapi.actionSystem.DataKey<String> = com.intellij.openapi.actionSystem.DataKey.create("deliberator.explorer.driftAddress")
        val EXPLORER: com.intellij.openapi.actionSystem.DataKey<AwsExplorerPanel> = com.intellij.openapi.actionSystem.DataKey.create("deliberator.explorer.panel")
    }

    /**
     * Appends [step] to `explore-<env>.scn` of the workspace's project (created with a header by the daemon when missing), opens it, runs
     * that step and peeks at the response when the run ends. EDT; the daemon calls are off it.
     */
    fun openStep(env: String, step: String) {
        val svc = WorkspaceService.getInstance(project)
        val name = ExplorerSteps.fileName(env)
        ApplicationManager.getApplication().executeOnPooledThread {
            val path = try {
                svc.call("explore.write", JsonObject().apply { addProperty("name", name); addProperty("text", ExplorerSteps.header(env)); addProperty("if-missing", true) })
                    .resultObject().get("path")?.asString ?: throw DaemonException("explore.write returned no path", "explore/no-path")
            } catch (e: DaemonException) {
                failed("AWS explorer", "open", e); return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({ appendAndRun(path, step) }, ModalityState.nonModal())
        }
    }

    /** The editor half of [openStep]: insert the step at the end of the document, save, run it, peek when it ends. EDT. */
    private fun appendAndRun(path: String, step: String) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) ?: return
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
        val runner = ScnRunner.getInstance(project)
        // the listener of this one run: a child of the panel (gone with the tab), gone earlier when the run ends or never starts
        val listener = Disposer.newDisposable(this, "explorer run of ${vf.name}")
        var seenRunning = false
        project.messageBus.connect(listener).subscribe(ScnRunListener.TOPIC, object : ScnRunListener {
            override fun stateChanged(path: String) {
                if (path != vf.path) return
                if (runner.isRunning(path)) seenRunning = true
                else if (seenRunning) Disposer.dispose(listener)   // the run ended without a result to read (refused, failed before run.started)
            }

            override fun runFinished(path: String, snapshot: RunSnapshot) {
                if (path != vf.path) return
                Disposer.dispose(listener)
                val resp = ResponsePaths.response(snapshot.sections[index])
                ApplicationManager.getApplication().invokeLater({
                    val psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? ScnFile
                    val s = psi?.steps?.getOrNull(index - 1)
                    if (resp != null && s != null && !editor.isDisposed) ResponsePeek.show(editor, s, PrettyJson.render(resp))
                }, ModalityState.nonModal())
            }
        })
        runner.run(vf, listOf(index))
        if (!runner.isRunning(vf.path)) Disposer.dispose(listener)   // refused ("already running"): nothing to wait for
    }
}
