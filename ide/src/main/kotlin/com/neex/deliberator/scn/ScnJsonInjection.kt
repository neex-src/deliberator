package com.neex.deliberator.scn

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiDocumentManager

/** Kinds whose body is the request document as JSON (the others: http carries a request line, sql is SQL). */
private val JSON_KINDS = setOf("sqs", "dynamodb", "eventbridge", "lambda", "scheduler")

/**
 * JSON in the body of the AWS steps: colours, brace matching, folding, the JSON plugin's own checks. A `{{var}}` is not part
 * of the injected text (the body is split around it) and stands in as `0`, so `"{{id}}"` and `{"qty": {{qty}}}` both stay valid JSON.
 */
class ScnJsonInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(ScnBody::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val body = context as? ScnBody ?: return
        if (!body.isValidHost) return
        val kind = (body.parent as? ScnStep)?.kind ?: return
        if (kind !in JSON_KINDS) return
        val json = Language.findLanguageByID("JSON") ?: return
        val parts = split(body.text) ?: return
        registrar.startInjecting(json)
        parts.forEachIndexed { i, r -> registrar.addPlace(if (i == 0) null else "0", null, body, r) }
        registrar.doneInjecting()
    }

    companion object {
        private val TEMPLATE = Regex("\\{\\{[^}\\n]*}}")

        /** The ranges of [text] outside its `{{...}}` variables; null when the text is not a JSON document (no `{` or `[` first). */
        fun split(text: String): List<TextRange>? {
            val first = text.firstOrNull { !it.isWhitespace() } ?: return null
            if (first != '{' && first != '[') return null
            val out = ArrayList<TextRange>()
            var at = 0
            for (m in TEMPLATE.findAll(text)) {
                out += TextRange(at, m.range.first)
                at = m.range.last + 1
            }
            out += TextRange(at, text.length)
            return out
        }
    }
}

/** Lets the body take injections: edits in the injected fragment go back through the document. */
class ScnBodyManipulator : AbstractElementManipulator<ScnBody>() {
    override fun handleContentChange(element: ScnBody, range: TextRange, newContent: String): ScnBody? {
        val file = element.containingFile
        val doc = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return element
        val start = element.textRange.startOffset
        doc.replaceString(start + range.startOffset, start + range.endOffset, newContent)
        PsiDocumentManager.getInstance(element.project).commitDocument(doc)
        return element
    }

    override fun getRangeInElement(element: ScnBody): TextRange = TextRange(0, element.textLength)
}
