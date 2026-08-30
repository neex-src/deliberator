package com.neex.tellij.java.summary

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Inliner's inline-frame recipe with a text panel instead of a nested editor: a header, a
 * monospace body in the editor font, a border, the whole thing embedded as a block inlay under a
 * line. `embed` is the one deliberately isolated call into the impl API.
 */
object InlayLook {

    fun header(text: String, onClick: () -> Unit): JComponent {
        val label = JBLabel(text)
        label.foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        val panel = JPanel(BorderLayout())
        panel.add(label, BorderLayout.WEST)
        panel.background = headerBackground()
        panel.isOpaque = true
        panel.border = JBUI.Borders.empty(2, 8)
        panel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        panel.toolTipText = "Click to close (Esc)"
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
        return panel
    }

    fun body(lines: List<String>): JComponent {
        val area = JTextArea(lines.joinToString("\n"))
        area.isEditable = false
        area.font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        area.background = frameBackground()
        area.foreground = EditorColorsManager.getInstance().globalScheme.defaultForeground
        area.border = JBUI.Borders.empty(4, 8)
        area.isOpaque = true
        return area
    }

    fun wrapper(header: JComponent, body: JComponent, leftIndentPx: Int): JPanel {
        val inner = JPanel(BorderLayout())
        inner.add(header, BorderLayout.NORTH)
        inner.add(body, BorderLayout.CENTER)
        inner.border = BorderFactory.createLineBorder(JBColor.border())
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = BorderFactory.createEmptyBorder(2, leftIndentPx, 2, 0)
        panel.add(inner, BorderLayout.CENTER)
        return panel
    }

    fun embed(editor: EditorEx, offset: Int, component: JComponent): Inlay<*>? {
        val props = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(), null,
            true,   // relatesToPrecedingText
            false,  // showAbove
            true,   // showWhenFolded
            false,  // fullWidth
            0,      // priority
            offset,
        )
        return EditorEmbeddedComponentManager.getInstance().addComponent(editor, component, props)
    }

    private fun frameBackground(): Color {
        val base = EditorColorsManager.getInstance().globalScheme.defaultBackground
        return if (ColorUtil.isDark(base)) ColorUtil.mix(base, Color.WHITE, 0.05) else ColorUtil.mix(base, Color.BLACK, 0.04)
    }

    private fun headerBackground(): Color {
        val base = EditorColorsManager.getInstance().globalScheme.defaultBackground
        return if (ColorUtil.isDark(base)) ColorUtil.mix(base, Color.WHITE, 0.11) else ColorUtil.mix(base, Color.BLACK, 0.08)
    }
}
