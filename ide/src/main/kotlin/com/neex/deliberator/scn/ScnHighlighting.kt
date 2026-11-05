package com.neex.deliberator.scn

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import javax.swing.Icon

object ScnColors {
    val HEAD_MARK = createTextAttributesKey("SCN_HEAD_MARK", D.METADATA)
    val KIND = createTextAttributesKey("SCN_KIND", D.KEYWORD)
    val VERB = createTextAttributesKey("SCN_VERB", D.FUNCTION_CALL)
    val TARGET = createTextAttributesKey("SCN_TARGET", D.CONSTANT)
    val NAME = createTextAttributesKey("SCN_NAME", D.FUNCTION_DECLARATION)
    val WORD = createTextAttributesKey("SCN_WORD", D.IDENTIFIER)
    val DIR_MARK = createTextAttributesKey("SCN_DIRECTIVE_MARK", D.METADATA)
    val DIR_NAME = createTextAttributesKey("SCN_DIRECTIVE_NAME", D.METADATA)
    val DIR_VALUE = createTextAttributesKey("SCN_DIRECTIVE_VALUE", D.STRING)
    val VARIABLE = createTextAttributesKey("SCN_VARIABLE", D.TEMPLATE_LANGUAGE_COLOR)
    val COMMENT = createTextAttributesKey("SCN_COMMENT", D.LINE_COMMENT)
    val BODY = createTextAttributesKey("SCN_BODY", D.IDENTIFIER)
    val MARKER = createTextAttributesKey("SCN_REGION_MARKER", D.DOC_COMMENT)
    val BAD = createTextAttributesKey("SCN_BAD", com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER)
}

class ScnSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ScnLexer()

    override fun getTokenHighlights(t: IElementType): Array<TextAttributesKey> = when (t) {
        ScnTokens.HEAD_MARK -> pack(ScnColors.HEAD_MARK)
        ScnTokens.KIND -> pack(ScnColors.KIND)
        ScnTokens.WORD -> pack(ScnColors.WORD)
        ScnTokens.TARGET -> pack(ScnColors.TARGET)
        ScnTokens.VARIABLE -> pack(ScnColors.VARIABLE)
        ScnTokens.COMMENT -> pack(ScnColors.COMMENT)
        ScnTokens.MARKER -> pack(ScnColors.MARKER)
        ScnTokens.DIR_MARK -> pack(ScnColors.DIR_MARK)
        ScnTokens.DIR_NAME -> pack(ScnColors.DIR_NAME)
        ScnTokens.DIR_VALUE -> pack(ScnColors.DIR_VALUE)
        ScnTokens.BODY -> pack(ScnColors.BODY)
        ScnTokens.BAD -> pack(ScnColors.BAD)
        else -> TextAttributesKey.EMPTY_ARRAY
    }
}

class ScnSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = ScnSyntaxHighlighter()
}

class ScnColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Step marker (###)", ScnColors.HEAD_MARK),
        AttributesDescriptor("Step kind", ScnColors.KIND),
        AttributesDescriptor("Step verb", ScnColors.VERB),
        AttributesDescriptor("Step target", ScnColors.TARGET),
        AttributesDescriptor("Step name", ScnColors.NAME),
        AttributesDescriptor("Step word (unclassified)", ScnColors.WORD),
        AttributesDescriptor("Directive marker (#)", ScnColors.DIR_MARK),
        AttributesDescriptor("Directive name (@assert)", ScnColors.DIR_NAME),
        AttributesDescriptor("Directive value", ScnColors.DIR_VALUE),
        AttributesDescriptor("Variable ({{x}})", ScnColors.VARIABLE),
        AttributesDescriptor("Comment", ScnColors.COMMENT),
        AttributesDescriptor("Generated-steps marker", ScnColors.MARKER),
        AttributesDescriptor("Body", ScnColors.BODY),
        AttributesDescriptor("Bad text", ScnColors.BAD),
    )

    override fun getDisplayName(): String = "Deliberator Scenario"
    override fun getIcon(): Icon = ScnFileType.icon
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getHighlighter(): SyntaxHighlighter = ScnSyntaxHighlighter()
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "verb" to ScnColors.VERB, "name" to ScnColors.NAME,
    )

    override fun getDemoText(): String = """
        # One order through the estate
        # @env local
        # @var msg = ord-{{uuid}}

        ### sqs <verb>send</verb> queue/inbox <name>send-order</name>
        # @attr id={{msg}}
        # @assert messageId matches /^[0-9a-f-]{36}${'$'}/

        ### dynamodb <verb>get</verb> table/messages <name>stored</name>
        # @until ${'$'}.status.S == RECEIVED within 20s
        {"Key": {"id": {"S": "{{msg}}"}}}
    """.trimIndent()
}

/**
 * Colours the words of a step line that only the registry can tell apart (verb, target, name) and the
 * `{{vars}}` inside them. With no registry yet (daemon never reached) the lexical rule is used, as the PSI's.
 * DumbAware: it reads the registry and the PSI only, so the colours stay while the project indexes.
 */
class ScnHeadAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is ScnStepHead) return
        val words = element.words
        val roles = ScnRoles.of(words.map { it.text }, ScnRegistry.getInstance().kinds()?.get(element.kind))
        fun mark(range: TextRange, key: TextAttributesKey) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
        }
        words.forEachIndexed { i, w ->
            when (i) {
                roles.verb -> mark(w.range, ScnColors.VERB)
                roles.name -> mark(w.range, ScnColors.NAME)
            }
            VAR.findAll(w.text).forEach { m ->
                mark(TextRange(w.range.startOffset + m.range.first, w.range.startOffset + m.range.last + 1), ScnColors.VARIABLE)
            }
        }
    }

    private companion object {
        val VAR = Regex("\\{\\{[^{}\\r\\n]*}}")
    }
}
