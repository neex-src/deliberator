package com.neex.deliberator.environments

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

/**
 * "New environment...": name, AWS profile, account, region, read-only. Create asks the daemon (`env.create`), which asks the profile who it
 * is and refuses, writing nothing, when the account differs or the profile cannot log in. The dialog stays open on a refusal and shows the
 * daemon's own words and hint under the form, so the profile or the account can be fixed and tried again; on success it closes and
 * [created] holds the account-check result. The daemon is asked off the EDT.
 */
class NewEnvironmentDialog(private val project: Project, private val onCreated: (EnvCreated) -> Unit = {}) : DialogWrapper(project) {
    private val name = JBTextField(20)
    private val profile = JBTextField(20)
    private val account = JBTextField(14)
    private val region = JBTextField("eu-central-1", 14)
    private val readOnly = JBCheckBox("Read-only: refuse every mutation (off: each mutation asks for a confirmation)", true)
    private val outcome = JBLabel(" ")
    private val busy = AtomicBoolean(false)

    /** The account-check result of a successful create (null until then). */
    @Volatile var created: EnvCreated? = null; private set

    /** The text under the form: the progress, the account-check result or the daemon's refusal. */
    val outcomeText: String get() = outcome.text.replace(Regex("<[^>]*>"), "").trim()

    /** True while a create is in flight. */
    val creating: Boolean get() = busy.get()

    init {
        title = "New Environment"
        setOKButtonText("Create")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(name).focused() }
        row("AWS profile:") { cell(profile).comment("A profile of the shared config and credentials files (static keys, sso_session, role_arn, credential_process).") }
        row("Account:") { cell(account).comment("The 12-digit account the profile must be in; Create asks the profile (sts:GetCallerIdentity) and refuses a mismatch.") }
        row("Region:") { cell(region) }
        row { cell(readOnly) }
        row { cell(outcome) }
    }

    override fun getPreferredFocusedComponent(): JComponent = name

    /** The form as typed. */
    fun form() = EnvForm(name.text.trim(), profile.text.trim(), account.text.trim(), region.text.trim(), readOnly.isSelected)

    override fun doValidate(): ValidationInfo? {
        val f = form()
        val p = f.problem ?: return null
        val field = when {
            f.name.isBlank() || f.name == "local" || !f.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) -> name
            f.profile.isBlank() -> profile
            !f.account.matches(Regex("\\d{12}")) -> account
            else -> region
        }
        return ValidationInfo(p, field)
    }

    /** Create does not close the dialog by itself: the answer decides. */
    override fun doOKAction() {
        if (doValidate() != null) { startTrackingValidation(); return }
        submit()
    }

    /** Asks the daemon to create the environment (off the EDT); the result lands in [outcomeText] and, on success, closes the dialog. */
    fun submit() {
        if (!busy.compareAndSet(false, true)) return
        val f = form()
        isOKActionEnabled = false
        show("Asking profile ${f.profile} who it is...", error = false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val svc = WorkspaceService.getInstance(project)
            try {
                val c = EnvironmentOps.create(svc, f)
                ApplicationManager.getApplication().invokeLater({
                    created = c
                    show(c.text(f.profile, f.region), error = false)
                    onCreated(c)
                    busy.set(false)
                    close(OK_EXIT_CODE)
                }, ModalityState.any())
            } catch (e: DaemonException) {
                ApplicationManager.getApplication().invokeLater({
                    show("Not created: ${e.message}" + (e.hint?.let { "\n$it" } ?: ""), error = true)
                    busy.set(false)
                    isOKActionEnabled = true
                }, ModalityState.any())
            }
        }
    }

    private fun show(text: String, error: Boolean) {
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")
        outcome.text = "<html><div style='width:420px'>$esc</div></html>"
        outcome.foreground = if (error) com.intellij.ui.JBColor.RED else com.intellij.util.ui.UIUtil.getLabelForeground()
    }

    internal fun type(n: String, p: String, a: String, r: String = "eu-central-1", ro: Boolean = true) {
        name.text = n; profile.text = p; account.text = a; region.text = r; readOnly.isSelected = ro
    }

    internal fun validation() = doValidate()
}
