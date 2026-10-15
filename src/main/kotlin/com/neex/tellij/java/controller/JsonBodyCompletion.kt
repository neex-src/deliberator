package com.neex.tellij.java.controller

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.neex.tellij.java.endpoint.DtoShape
import com.neex.tellij.java.endpoint.EndpointResolver
import com.neex.tellij.lang.TellijFile
import com.neex.tellij.lang.TellijFileType
import com.neex.tellij.model.Block
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.StepError

/** The `@RequestBody` shape of a controller block, or null when it cannot be known right now. */
internal fun bodyShapeOf(project: Project, block: Block): DtoShape? {
    if (block.kind != "controller" || block.name == null || DumbService.isDumb(project)) return null
    return try {
        ReadAction.compute<DtoShape?, RuntimeException> {
            val m = EndpointResolver.findMethod(project, block.name, block.directive("method"))
            EndpointResolver.resolve(m)?.body
        }
    } catch (e: StepError) { null } catch (e: IndexNotReadyException) { null }
}

/**
 * Inside a controller block's JSON body, the keys are the `@RequestBody` class's fields — at the
 * caret's nesting level, minus the ones already there, with type and required-ness as hints.
 */
class JsonBodyCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.fileType != TellijFileType) return
        val project = parameters.editor.project ?: return
        val document = parameters.editor.document
        val offset = parameters.editor.caretModel.offset
        val model = WorkbenchParser.parse(document.immutableCharSequence)
        val block = model.blockAt(offset) ?: return
        val body = block.body ?: return
        if (offset < body.start || offset > body.end) return
        val bodyText = document.immutableCharSequence.subSequence(body.start, body.end).toString()
        val pos = JsonKeyScanner.pathAt(bodyText, offset - body.start) ?: return
        if (!pos.atKey) return
        val shape = ControllerSource.shapeAt(bodyShapeOf(project, block), pos.path) ?: return
        val quoted = pos.partial.isNotEmpty() || (offset > 0 && document.immutableCharSequence[offset - 1] == '"' && pos.partial.isEmpty() && insideOpenQuote(bodyText, offset - body.start))
        val r = result.withPrefixMatcher(if (quoted) "\"" + pos.partial else pos.partial)
        for (f in shape.fields) {
            if (f.name in pos.siblings) continue
            r.addElement(
                LookupElementBuilder.create("\"${f.name}\": ").withPresentableText(f.name)
                    .withTypeText(f.type + if (f.required) " · required" else "").withIcon(AllIcons.Nodes.Field)
                    .withInsertHandler { ctx, _ ->
                        // A closing quote the user already typed after the caret is now redundant.
                        val doc = ctx.document
                        if (ctx.tailOffset < doc.textLength && doc.charsSequence[ctx.tailOffset] == '"') doc.deleteString(ctx.tailOffset, ctx.tailOffset + 1)
                    }
            )
        }
        r.stopHere()
    }

    private fun insideOpenQuote(text: String, offset: Int): Boolean {
        var quotes = 0
        for (i in 0 until minOf(offset, text.length)) if (text[i] == '"' && (i == 0 || text[i - 1] != '\\')) quotes++
        return quotes % 2 == 1
    }
}

/** Weak warnings for keys the request class does not have, and required keys that are missing. */
class JsonBodyAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? TellijFile ?: return
        val project = file.project
        val text = file.text
        val model = WorkbenchParser.parse(text)
        for (block in model.blocks) {
            val body = block.body ?: continue
            if (block.kind != "controller") continue
            val root = bodyShapeOf(project, block) ?: continue
            val bodyText = text.substring(body.start, body.end)
            val keys = JsonKeyScanner.keys(bodyText)
            for (k in keys) {
                val shape = ControllerSource.shapeAt(root, k.path) ?: continue
                if (shape.fields.none { it.name == k.name }) {
                    holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "no field '${k.name}' in ${shape.type}")
                        .range(TextRange(body.start + k.start, body.start + k.end)).create()
                }
            }
            val rootKeys = keys.filter { it.path.isEmpty() }.map { it.name }.toSet()
            val missing = root.fields.filter { it.required && it.name !in rootKeys }.map { it.name }
            if (missing.isNotEmpty() && bodyText.trimStart().startsWith("{")) {
                val brace = body.start + bodyText.indexOf('{')
                holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "${root.type} requires ${missing.joinToString(", ")}")
                    .range(TextRange(brace, brace + 1)).create()
            }
        }
    }
}
