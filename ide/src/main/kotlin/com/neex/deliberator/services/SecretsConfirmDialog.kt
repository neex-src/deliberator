package com.neex.deliberator.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.Dimension
import javax.swing.JComponent

/** One of the daemon's two confirmations for a start with real secret values: its `summary` verbatim, "Confirm N of M" or Cancel. */
class SecretsConfirmDialog(project: Project?, private val c: ServiceOps.SecretsConfirmation) : DialogWrapper(project) {
    /** The OK button's text (also what tests read). */
    val confirmLabel = "Confirm ${c.step} of ${c.of}"

    init {
        title = "Real secret values" + (c.env?.let { " from $it" } ?: "")
        setOKButtonText(confirmLabel)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val text = JBTextArea(c.summary).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; caretPosition = 0 }
        return JBScrollPane(text).apply { preferredSize = Dimension(640, 220) }
    }

    /** The text shown (tests). */
    internal fun shownText() = c.summary
}
