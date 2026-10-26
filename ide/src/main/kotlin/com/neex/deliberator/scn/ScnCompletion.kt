package com.neex.deliberator.scn

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

/** Where the caret is, from the text of its line before it (and nothing else): the pure half of completion. */
sealed class ScnContext {
    /** One to three `#` at the start of an otherwise empty line: offers `### kind`. */
    data class StepStart(val prefix: String) : ScnContext()
    /** On a `### ` line: [done] are the words before the one being typed ([done].first is the kind). */
    data class StepLine(val done: List<String>, val prefix: String) : ScnContext()
    /** `# @prefix` */
    data class DirectiveName(val prefix: String) : ScnContext()
    /** `# @name args prefix` */
    data class DirectiveValue(val name: String, val args: String, val prefix: String) : ScnContext()
    /** Inside an unclosed `{{`. */
    data class Variable(val prefix: String) : ScnContext()
    object None : ScnContext()

    companion object {
        private val HASHES = Regex("#{1,3}")
        private val DIR_NAME = Regex("#\\s*@([A-Za-z0-9._-]*)")
        private val DIR_VALUE = Regex("#\\s*@([A-Za-z][A-Za-z0-9._-]*)\\s+(.*)", RegexOption.DOT_MATCHES_ALL)

        fun of(before: String): ScnContext {
            val open = before.lastIndexOf("{{")
            if (open >= 0 && before.lastIndexOf("}}") < open) return Variable(before.substring(open + 2))
            if (HASHES.matches(before)) return StepStart(before)
            if (before.startsWith("### ")) {
                val rest = before.substring(4)
                val words = rest.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                return if (rest.isEmpty() || rest.last().isWhitespace()) StepLine(words, "")
                else StepLine(words.dropLast(1), words.last())
            }
            DIR_NAME.matchEntire(before)?.let { return DirectiveName(it.groupValues[1]) }
            DIR_VALUE.matchEntire(before)?.let { m ->
                val args = m.groupValues[2]
                val prefix = if (args.isEmpty() || args.last().isWhitespace()) "" else args.split(Regex("\\s+")).last()
                return DirectiveValue(m.groupValues[1], args.dropLast(prefix.length), prefix)
            }
            return None
        }
    }
}

/** What is offered for a [ScnContext]: the logic of the completion contributor over plain data, testable without a lookup. */
object ScnProposals {
    /** [suffix] is inserted after [text] when it is chosen (a space after a directive name). */
    class Item(val text: String, val type: String? = null, val tail: String? = null, val suffix: String = "")

    fun forContext(
        ctx: ScnContext, kinds: ScnKinds?, targets: List<String>, file: ScnFile?, offset: Int,
        response: List<ResponsePath> = emptyList(),
        live: Map<String, List<LiveResource>> = emptyMap(),
    ): List<Item> = when (ctx) {
        is ScnContext.StepStart -> kinds?.kinds?.map { Item("### ${it.kind}", "kind") } ?: emptyList()
        is ScnContext.StepLine -> stepLine(ctx, kinds, targets, live)
        is ScnContext.DirectiveName -> directiveNames(kinds, file, offset)
        is ScnContext.DirectiveValue -> directiveValue(ctx, kinds, file, offset, response)
        is ScnContext.Variable -> variables(file, offset, targets)
        ScnContext.None -> emptyList()
    }

    private fun stepLine(ctx: ScnContext.StepLine, kinds: ScnKinds?, targets: List<String>, live: Map<String, List<LiveResource>>): List<Item> {
        if (kinds == null) return emptyList()
        if (ctx.done.isEmpty()) return kinds.kinds.map { Item(it.kind, "kind", tail = it.verbs.takeIf { v -> v.isNotEmpty() }?.joinToString(" | ", " ")) }
        val kind = kinds[ctx.done[0]] ?: return emptyList()
        val after = ctx.done.drop(1)
        val verbs = kind.verbs.map { Item(it, "verb of ${kind.kind}") }
        val liveNames = AwsResources.TARGET_KIND[kind.kind]?.let { live[it] }.orEmpty()
            .filter { r -> r.name !in targets }.map { Item(it.name, "live ${kind.kind}") }
        val tgs = if (kind.target == "none") emptyList() else targets.map { Item(it, "target") } + liveNames
        return when {
            after.isEmpty() -> verbs + (if (kind.verbOptional) tgs else emptyList())
            after.size == 1 && after[0] in kind.verbs -> tgs
            else -> emptyList()
        }
    }

    private fun kindAt(file: ScnFile?, offset: Int, kinds: ScnKinds?): Pair<ScnStep?, ScnKind?> {
        val step = file?.stepAt(offset)
        return step to kinds?.get(step?.kind)
    }

    private fun directiveNames(kinds: ScnKinds?, file: ScnFile?, offset: Int): List<Item> {
        val (step, kind) = kindAt(file, offset, kinds)
        val specs = when {
            step == null -> ScnKinds.HEADER_DIRECTIVES
            kind != null -> kind.directives
            else -> ScnKinds.ENGINE_DIRECTIVES
        }
        // the directive being typed is in the PSI too: it does not count against its own name
        val here = (step?.directives ?: file?.header?.directives ?: emptyList()).filter { !(it.textRange.startOffset <= offset && offset <= it.textRange.endOffset) }
        val used = here.map { it.key }.toSet()
        return specs.filter { it.repeatable || it.name !in used }
            .map { Item(it.name, if (it.engine) "engine" else kind?.kind ?: "header", tail = it.hint?.let { h -> " $h" }, suffix = " ") }
    }

    private fun directiveValue(ctx: ScnContext.DirectiveValue, kinds: ScnKinds?, file: ScnFile?, offset: Int, response: List<ResponsePath>): List<Item> {
        val (step, kind) = kindAt(file, offset, kinds)
        val facts = kind?.facts?.map { Item(it.name.removeSuffix("*"), "fact (${it.type})") } ?: emptyList()
        val roots = kind?.captureRoots?.map { Item(it, "path root") } ?: emptyList()
        val paths = response.map { Item(it.path, "= ${it.preview}") }
        val spec = (kind?.directives ?: ScnKinds.ENGINE_DIRECTIVES).firstOrNull { it.name == ctx.name }
            ?: ScnKinds.HEADER_DIRECTIVES.firstOrNull { step == null && it.name == ctx.name }
        val firstWord = ctx.args.isBlank()
        return when (ctx.name) {
            "assert", "until" -> if (firstWord) facts + paths + roots else if (ctx.args.trim().split(Regex("\\s+")).size == 1) (spec?.values ?: emptyList()).map { Item(it, "operator") } else emptyList()
            "capture" -> if (ctx.args.trimEnd().endsWith("=")) facts + paths + roots else emptyList()
            else -> if (firstWord) (spec?.values ?: emptyList()).map { Item(it, "value") } else emptyList()
        }
    }

    private fun variables(file: ScnFile?, offset: Int, targets: List<String>): List<Item> {
        val out = ArrayList<Item>()
        // only what is defined above the caret; the directive under the caret is not (its range holds the offset)
        file?.header?.directives?.filter { it.key == "var" && it.textRange.endOffset <= offset }
            ?.mapNotNull { ScnDirective.assignedName(it.value) }?.forEach { out += Item(it, "@var") }
        file?.steps?.forEach { s ->
            s.directives.filter { it.key == "capture" && it.textRange.endOffset <= offset }
                .mapNotNull { ScnDirective.assignedName(it.value) }
                .forEach { out += Item(it, "@capture of step ${s.index}") }
        }
        ScnKinds.BUILTIN_VARS.forEach { out += Item(it, "built in") }
        targets.forEach { out += Item(it, "target") }
        return out.distinctBy { it.text }
    }
}

/**
 * Completion of `.scn`: kinds, verbs, targets, directive names, facts, variables. The data comes from [ScnRegistry] and
 * [AwsResources] caches only (a stale cache is refreshed on a pooled thread for the next completion): the completion thread
 * never waits for the daemon and never starts one; with no daemon and no saved copy the static part (engine directives,
 * built-in variables, the file's own vars and captures) still answers. DumbAware: no index is used.
 */
class ScnCompletionContributor : CompletionContributor(), com.intellij.openapi.project.DumbAware {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(ScnLanguage), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile as? ScnFile ?: return
                val text = parameters.originalFile.text
                val offset = parameters.offset.coerceAtMost(text.length)
                val lineStart = text.lastIndexOf('\n', offset - 1) + 1
                val ctx = ScnContext.of(text.substring(lineStart, offset))
                if (ctx === ScnContext.None) return
                val registry = ScnRegistry.getInstance()
                val project = file.project
                val needsTargets = ctx is ScnContext.Variable || ctx is ScnContext.StepLine
                if (needsTargets && registry.needsTargets(project)) registry.refreshTargetsAsync(project)
                val targets = if (needsTargets) registry.targets(project) else emptyList()
                val prefix = when (ctx) {
                    is ScnContext.StepStart -> ctx.prefix
                    is ScnContext.StepLine -> ctx.prefix
                    is ScnContext.DirectiveName -> ctx.prefix
                    is ScnContext.DirectiveValue -> ctx.prefix
                    is ScnContext.Variable -> ctx.prefix
                    ScnContext.None -> ""
                }
                val set = result.withPrefixMatcher(prefix)
                val response = if (ctx is ScnContext.DirectiveValue) latestResponse(file, offset) else emptyList()
                val live = if (ctx is ScnContext.StepLine && ctx.done.size >= 1) AwsResources.getInstance().get(project) else emptyMap()
                for (i in ScnProposals.forContext(ctx, registry.kinds(), targets, file, offset, response, live)) set.addElement(element(i, ctx))
                set.stopHere()
            }
        })
    }

    /** The paths of the response the step under the caret got in the latest run of this file (in memory only, never a daemon call). */
    private fun latestResponse(file: ScnFile, offset: Int): List<ResponsePath> {
        val step = file.stepAt(offset) ?: return emptyList()
        val path = file.virtualFile?.path ?: file.originalFile.virtualFile?.path ?: return emptyList()
        val snap = com.neex.deliberator.run.ScnRunner.getInstance(file.project).state(path).snapshot ?: return emptyList()
        return ResponsePaths.of(ResponsePaths.response(snap.sections[step.index]))
    }

    private fun element(i: ScnProposals.Item, ctx: ScnContext): LookupElement {
        var b = LookupElementBuilder.create(i.text)
        if (i.type != null) b = b.withTypeText(i.type, true)
        if (i.tail != null) b = b.withTailText(i.tail, true)
        if (ctx is ScnContext.Variable) b = b.withInsertHandler(CLOSE_VARIABLE)
        else if (i.suffix.isNotEmpty()) b = b.withInsertHandler(InsertHandler { c, _ ->
            val t = c.tailOffset
            c.document.insertString(t, i.suffix)
            c.editor.caretModel.moveToOffset(t + i.suffix.length)
        })
        return b
    }

    private companion object {
        val CLOSE_VARIABLE = InsertHandler<LookupElement> { ctx, _ ->
            val doc = ctx.document
            val tail = ctx.tailOffset
            if (!doc.charsSequence.subSequence(tail, minOf(tail + 2, doc.textLength)).startsWith("}}")) {
                doc.insertString(tail, "}}")
            }
            ctx.editor.caretModel.moveToOffset(tail + 2)
        }
    }
}

/**
 * Opens the completion popup after `@`, after `{` and after the space of a `### ` step head: none of them is an identifier
 * character, which is all the platform pops up for on its own. (This replaces the deprecated `invokeAutoPopup` of the contributor.)
 */
class ScnAutoPopup : com.intellij.codeInsight.editorActions.TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: com.intellij.openapi.project.Project, editor: com.intellij.openapi.editor.Editor, file: com.intellij.psi.PsiFile): Result {
        if (file !is ScnFile) return Result.CONTINUE
        val doc = editor.document
        val offset = editor.caretModel.offset.coerceIn(0, doc.textLength)
        val prefix = doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(doc.getLineNumber(offset)), offset))
        if (!triggers(charTyped, prefix)) return Result.CONTINUE
        com.intellij.codeInsight.AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }

    companion object {
        /** [linePrefix] is the line up to the caret (with or without the typed character, depending on when the platform asks). */
        fun triggers(charTyped: Char, linePrefix: String): Boolean =
            charTyped == '@' || charTyped == '{' || (charTyped == ' ' && Regex("###\\s?").matches(linePrefix))
    }
}
