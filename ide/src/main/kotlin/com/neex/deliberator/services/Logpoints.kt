package com.neex.deliberator.services

import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl

/**
 * Non-suspending logging breakpoints (brief 4.B, investigated in): the platform's own line breakpoint with "Suspend: none" and
 * "Evaluate and log", set in one step. While the debugger is attached to a service its output goes to the IDE's debug console, not into the
 * container log, so it is evidence of a line being hit and not something `logs.match` can highlight.
 */
object Logpoints {
    /** A line breakpoint on 0-based [line0] of [file] that logs [expression] (a Java expression of the debuggee) and does not suspend. EDT. */
    fun add(project: Project, file: VirtualFile, line0: Int, expression: String): XLineBreakpoint<*> {
        val type = XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType::class.java)
        val mgr = XDebuggerManager.getInstance(project).breakpointManager
        return runWriteAction {
            val bp = mgr.addLineBreakpoint(type, file.url, line0, type.createBreakpointProperties(file, line0))
            bp.suspendPolicy = SuspendPolicy.NONE
            bp.isLogMessage = true
            bp.logExpressionObject = XExpressionImpl.fromText(expression) as XExpression
            bp
        }
    }
}

/** `deliberator.marker.logpoint`: a logging breakpoint at the caret; asks for the expression, default a message naming the file and line. */
class AddLogpointAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR)?.virtualFileOrNull()?.extension == "java"
    }

    private fun com.intellij.openapi.editor.Editor.virtualFileOrNull() = FileDocumentManager.getInstance().getFile(document)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = editor.virtualFileOrNull() ?: return
        val line = editor.caretModel.logicalPosition.line
        val default = "\"deliberator: ${file.name}:${line + 1}\""
        val expr = Messages.showInputDialog(project, "Java expression to log when this line is hit (the debuggee is not suspended):", "Logging Breakpoint", null, default, null) ?: return
        Logpoints.add(project, file, line, expr)
    }
}
