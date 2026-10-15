package com.neex.tellij.editor

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.neex.tellij.engine.RunSession
import com.neex.tellij.lang.TellijFile
import com.neex.tellij.lang.TellijFileType
import com.neex.tellij.model.WorkbenchModel
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.DirectiveSpec
import com.neex.tellij.spi.SourceRegistry
import com.neex.tellij.vars.EnvFile

/**
 * Completion from the text, not the PSI: the line before the caret decides the spot — a kind
 * after `###` or `# @connect`, a directive after `# @`, a directive's known values, a `@connect`
 * option, or a variable inside `{{`. What is offered comes from the sources themselves
 * ([com.neex.tellij.spi.SourceKind.directives]), so a new kind documents itself.
 */
class TellijCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.fileType != TellijFileType) return
        val document = parameters.editor.document
        val offset = parameters.editor.caretModel.offset
        val text = document.immutableCharSequence
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val before = text.subSequence(lineStart, offset).toString()
        val after = text.subSequence(offset, minOf(text.length, offset + 2)).toString()
        val model = WorkbenchParser.parse(text)
        val block = model.blockAt(offset)

        val (prefix, items) = variants(before, after, model, block?.kind, offset < model.headerRange.end, envKeys(document)) ?: return
        val r = result.withPrefixMatcher(prefix)
        items.forEach { r.addElement(it) }
        r.stopHere()
    }

    private fun envKeys(document: com.intellij.openapi.editor.Document): List<String> =
        EnvFile.load(RunSession.directoryOf(document)).keys.sorted()

    companion object {
        private val KIND = Regex("""^###\s*([\w\-]*)$""")
        private val CONNECT_KIND = Regex("""^#\s*@connect\s+([\w\-]*)$""")
        private val CONNECT_OPTION = Regex("""^#\s*@connect\s+([\w\-]+)\s+\S+\s+(?:.*\s)?([\w.\-]*)$""")
        private val DIRECTIVE = Regex("""^#\s*@([\w\-]*)$""")
        private val DIRECTIVE_VALUE = Regex("""^#\s*@([\w\-]+)\s+(\S*)$""")
        private val VAR = Regex("""\{\{\s*([\w.\-]*)$""")
        private val BUILTINS = listOf("uuid" to "8 hex chars, fresh per use", "uuid.full" to "a full UUID", "now" to "local ISO timestamp", "now.epoch" to "epoch millis")

        /** Pure: (prefix to match, elements) or null when the caret is not at a completable spot. */
        fun variants(before: String, after: String, model: WorkbenchModel, kind: String?, inHeader: Boolean, envKeys: List<String>): Pair<String, List<LookupElement>>? {
            VAR.find(before)?.let { m ->
                val names = LinkedHashMap<String, String>()
                model.vars.forEach { names[it.name] = "@var" }
                model.blocks.flatMap { it.directives("capture") }.forEach { c -> c.substringBefore('=').trim().takeIf { it.isNotEmpty() }?.let { names[it] = "@capture" } }
                BUILTINS.forEach { (n, h) -> names[n] = h }
                envKeys.forEach { names["env.$it"] = "tellij-env.json" }
                val close = if (after.startsWith("}}")) "" else "}}"
                return m.groupValues[1] to names.map { (n, h) ->
                    LookupElementBuilder.create(n).withTypeText(h).withIcon(AllIcons.Nodes.Variable)
                        .withInsertHandler { ctx, _ -> if (close.isNotEmpty()) { ctx.document.insertString(ctx.tailOffset, close); ctx.editor.caretModel.moveToOffset(ctx.tailOffset) } }
                }
            }
            KIND.matchEntire(before)?.let { m -> return m.groupValues[1] to kinds("block kind — ### kind name") }
            CONNECT_KIND.matchEntire(before)?.let { m -> return m.groupValues[1] to kinds("connection kind") }
            CONNECT_OPTION.matchEntire(before)?.let { m ->
                val specs = SourceRegistry.byId(m.groupValues[1])?.connectOptions ?: return null
                return m.groupValues[2] to specs.map { s ->
                    LookupElementBuilder.create(if (s.name.endsWith(".")) s.name else s.name + "=").withPresentableText(s.name).withTypeText(s.hint).withIcon(AllIcons.Nodes.Property)
                }
            }
            DIRECTIVE.matchEntire(before)?.let { m ->
                val specs = if (inHeader || kind == null) DirectiveSpec.HEADER else DirectiveSpec.COMMON + (SourceRegistry.byId(kind)?.directives ?: emptyList())
                return m.groupValues[1] to specs.distinctBy { it.name }.map { s ->
                    LookupElementBuilder.create(s.name + " ").withPresentableText("@" + s.name).withTypeText(s.hint).withIcon(AllIcons.Nodes.Annotationtype)
                }
            }
            DIRECTIVE_VALUE.matchEntire(before)?.let { m ->
                val all = (kind?.let { SourceRegistry.byId(it)?.directives } ?: emptyList()) + DirectiveSpec.COMMON + DirectiveSpec.HEADER
                val specs = all.filter { it.name == m.groupValues[1] && it.values.isNotEmpty() }
                if (specs.isEmpty()) return null
                return m.groupValues[2] to specs.flatMap { s -> s.values.map { it to s.hint } }.distinctBy { it.first }
                    .map { (v, hint) -> LookupElementBuilder.create(v).withTypeText(hint) }
            }
            return null
        }

        private fun kinds(hint: String): List<LookupElement> =
            SourceRegistry.all().map { LookupElementBuilder.create(it.id).withTypeText(hint).withIcon(AllIcons.Nodes.Plugin) }
    }
}

/** `@` and the second `{` open the popup right away; letters already do. */
class TellijTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is TellijFile) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = editor.document.immutableCharSequence
        val trigger = c == '@' || (c == '{' && offset > 0 && text[offset - 1] == '{') ||
            (c == '"' && WorkbenchParser.parse(text).blockAt(offset)?.let { b -> b.body != null && offset > b.body.start } == true)
        if (trigger) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }
}
