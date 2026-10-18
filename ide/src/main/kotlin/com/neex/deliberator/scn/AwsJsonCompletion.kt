package com.neex.deliberator.scn

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Member names and enum values in the JSON body of an `aws` step, from the operation's request shape: required members
 * first (bold), then the rest, the ones already written left out. Only inside a `.scn` body, only for `### aws service.Op`.
 */
class AwsJsonCompletion : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val project = parameters.originalFile.project
                val host = InjectedLanguageManager.getInstance(project).getInjectionHost(parameters.originalFile) as? ScnBody ?: return
                val step = host.parent as? ScnStep ?: return
                if (step.kind != "aws") return
                val verb = step.verb ?: return
                val root = AwsModels.getInstance().input(project, verb) ?: return
                val pos = parameters.position
                val literal = PsiTreeUtil.getParentOfType(pos, JsonStringLiteral::class.java, false)
                val prop = literal?.parent as? JsonProperty
                val isValue = prop != null && prop.value === literal
                if (isValue) {
                    val node = root.at(pathOf(prop!!)) ?: return
                    node.enum.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("enum", true)) }
                    AwsResources.forMember(verb.substringBefore('.'), prop.name)?.let { (kind, byRef) ->
                        AwsResources.getInstance().get(project)[kind].orEmpty().forEach { r ->
                            val text = if (byRef) r.ref ?: r.name else r.name
                            result.addElement(LookupElementBuilder.create(text).withTypeText("live $kind", true))
                        }
                    }
                    result.stopHere()
                    return
                }
                val obj = PsiTreeUtil.getParentOfType(pos, JsonObject::class.java) ?: return
                val node = root.at(pathOf(obj)) ?: return
                if (node.type != "structure") return
                val present = obj.propertyList.map { it.name }.toSet() - setOfNotNull((literal?.parent as? JsonProperty)?.name)
                val quoted = literal != null
                for (name in node.order) {
                    if (name in present) continue
                    val m = node.members[name] ?: continue
                    val req = name in node.required
                    var b = LookupElementBuilder.create(name).withTypeText(describe(m) + if (req) "  required" else "", true)
                    if (req) b = b.bold()
                    if (!quoted) b = b.withLookupString("\"$name\"").withInsertHandler { c, _ ->
                        c.document.replaceString(c.startOffset, c.tailOffset, "\"$name\": ")
                        c.editor.caretModel.moveToOffset(c.startOffset + name.length + 4)
                    }
                    result.addElement(b)
                }
                result.stopHere()
            }
        })
    }

    private fun describe(n: AwsNode): String = when (n.type) {
        "list" -> "[${n.member?.let { describe(it) } ?: "?"}]"
        "structure" -> "{…}"
        "map" -> "map"
        else -> n.type + if (n.enum.isNotEmpty()) " enum" else ""
    }

    /** Member names from the document's root object down to [from]; a list on the way is one [AwsNode.ELEMENT]. */
    private fun pathOf(from: PsiElement): List<String> {
        val out = ArrayList<String>()
        var e: PsiElement? = from
        while (e != null && e !is com.intellij.psi.PsiFile) {
            val p = e.parent
            when {
                e is JsonProperty -> out += e.name
                e is JsonArray -> out += AwsNode.ELEMENT
            }
            e = p
        }
        return out.reversed()
    }
}
