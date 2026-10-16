package com.neex.deliberator.scn

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

class ScnElementType(debugName: String) : IElementType(debugName, ScnLanguage)

object ScnElements {
    val FILE = IFileElementType(ScnLanguage)
    val HEADER = ScnElementType("HEADER")
    val STEP = ScnElementType("STEP")
    val STEP_HEAD = ScnElementType("STEP_HEAD")
    val DIRECTIVE = ScnElementType("DIRECTIVE")
    val BODY = ScnElementType("BODY")
}

/**
 * Flat tree: file -> [ScnHeader]? -> [ScnStep]* -> ([ScnStepHead], [ScnDirective]*, [ScnBody]?). Loose
 * end-of-line tokens stay directly under the node they close. The header holds [ScnDirective]s too
 * (`@env`, `@var`).
 */
class ScnParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        if (!builder.eof() && builder.tokenType != ScnTokens.HEAD_MARK) {
            val h = builder.mark()
            while (!builder.eof() && builder.tokenType != ScnTokens.HEAD_MARK) {
                if (builder.tokenType == ScnTokens.DIR_MARK) directive(builder) else builder.advanceLexer()
            }
            h.done(ScnElements.HEADER)
        }
        while (!builder.eof()) {
            val step = builder.mark()
            val head = builder.mark()
            line(builder)
            head.done(ScnElements.STEP_HEAD)
            while (!builder.eof() && builder.tokenType != ScnTokens.HEAD_MARK) {
                when (builder.tokenType) {
                    ScnTokens.DIR_MARK -> directive(builder)
                    ScnTokens.EOL -> builder.advanceLexer()
                    else -> {
                        val b = builder.mark()
                        while (!builder.eof() && builder.tokenType != ScnTokens.HEAD_MARK) builder.advanceLexer()
                        b.done(ScnElements.BODY)
                    }
                }
            }
            step.done(ScnElements.STEP)
        }
        file.done(root)
        return builder.treeBuilt
    }

    private fun directive(b: PsiBuilder) {
        val d = b.mark()
        line(b)
        d.done(ScnElements.DIRECTIVE)
    }

    /** Consumes up to and including the end of line. */
    private fun line(b: PsiBuilder) {
        while (!b.eof()) {
            val t = b.tokenType
            b.advanceLexer()
            if (t == ScnTokens.EOL) break
        }
    }
}

class ScnParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = ScnLexer()
    override fun createParser(project: Project?): PsiParser = ScnParser()
    override fun getFileNodeType(): IFileElementType = ScnElements.FILE
    override fun getCommentTokens(): TokenSet = TokenSet.create(ScnTokens.COMMENT)
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createFile(viewProvider: FileViewProvider): PsiFile = ScnFile(viewProvider)
    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        ScnElements.HEADER -> ScnHeader(node)
        ScnElements.STEP -> ScnStep(node)
        ScnElements.STEP_HEAD -> ScnStepHead(node)
        ScnElements.DIRECTIVE -> ScnDirective(node)
        ScnElements.BODY -> ScnBody(node)
        else -> ASTWrapperPsiElement(node)
    }
}

/** Line numbers are 1-based throughout, the numbers `scenario.parse` uses. */
class ScnFile(provider: FileViewProvider) : PsiFileBase(provider, ScnLanguage) {
    override fun getFileType(): FileType = ScnFileType

    val header: ScnHeader? get() = PsiTreeUtil.getChildOfType(this, ScnHeader::class.java)
    val steps: List<ScnStep> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ScnStep::class.java)

    private class Lines(val starts: IntArray, val lastContent: Int)

    private val lines: Lines
        get() = CachedValuesManager.getCachedValue(this) {
            val t = text
            val s = ArrayList<Int>()
            s += 0
            for (i in t.indices) if (t[i] == '\n') s += i + 1
            val starts = s.toIntArray()
            var last = 0
            for (k in starts.indices.reversed()) {
                val from = starts[k]
                val to = if (k + 1 < starts.size) starts[k + 1] - 1 else t.length
                val empty = to <= from || (to - from == 1 && t[from] == '\r')
                if (!empty) { last = k + 1; break }
            }
            CachedValueProvider.Result.create(Lines(starts, last), this)
        }

    fun lineOf(offset: Int): Int {
        val s = lines.starts
        var lo = 0
        var hi = s.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (s[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    /** The last line that has any character (what `scenario.parse` takes as the end of the last step). */
    val lastContentLine: Int get() = lines.lastContent

    /** The step whose lines contain [offset]: the last step that starts at or before it; null in the header. */
    fun stepAt(offset: Int): ScnStep? = steps.lastOrNull { it.textRange.startOffset <= offset }
}

class ScnHeader(node: ASTNode) : ASTWrapperPsiElement(node) {
    val directives: List<ScnDirective> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ScnDirective::class.java)

    /** Names declared by `# @var name = value`. */
    val varNames: List<String> get() = directives.filter { it.key == "var" }.mapNotNull { ScnDirective.assignedName(it.value) }
}

class ScnStep(node: ASTNode) : ASTWrapperPsiElement(node) {
    val head: ScnStepHead? get() = PsiTreeUtil.getChildOfType(this, ScnStepHead::class.java)
    val directives: List<ScnDirective> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ScnDirective::class.java)
    val body: ScnBody? get() = PsiTreeUtil.getChildOfType(this, ScnBody::class.java)

    private val scnFile: ScnFile get() = containingFile as ScnFile

    /** 1-based position among the steps of the file (`index` of `scenario.parse`). */
    val index: Int
        get() {
            var i = 1
            var s = prevSibling
            while (s != null) { if (s is ScnStep) i++; s = s.prevSibling }
            return i
        }

    private val nextStep: ScnStep? get() {
        var s = nextSibling
        while (s != null && s !is ScnStep) s = s.nextSibling
        return s as? ScnStep
    }

    /** The `###` line (`line` of `scenario.parse`). */
    val startLine: Int get() = scnFile.lineOf(textRange.startOffset)

    /** The line before the next step; of the last step the last line with a character (`end-line`). */
    val endLine: Int get() = nextStep?.let { it.startLine - 1 } ?: maxOf(scnFile.lastContentLine, startLine)

    val kind: String? get() = head?.kind

    /** Verb, target and name of the step line, told apart with the cached registry when there is one (else lexically). */
    private val roles: Triple<String?, String?, String?>
        get() {
            val h = head ?: return Triple(null, null, null)
            val words = h.words.map { it.text }
            val r = ScnRoles.of(words, ScnRegistry.getInstance().kinds()?.get(kind))
            fun w(i: Int) = if (i >= 0) words.getOrNull(i) else null
            return Triple(w(r.verb), w(r.target), w(r.name))
        }
    val verb: String? get() = roles.first
    val target: String? get() = roles.second
    val stepName: String? get() = directives.firstOrNull { it.key == "name" }?.value ?: roles.third

    /** Text of the step line after the `###`: `kind verb target name`. */
    val headText: String get() = head?.lineText ?: ""
}

class ScnStepHead(node: ASTNode) : ASTWrapperPsiElement(node) {
    class Word(val text: String, val range: TextRange, val target: Boolean)

    private fun leaves(type: IElementType) = generateSequence(firstChild) { it.nextSibling }.filter { it.node.elementType == type }

    val kind: String? get() = leaves(ScnTokens.KIND).firstOrNull()?.text

    /** Words after the kind in file offsets. */
    val words: List<Word>
        get() = generateSequence(firstChild) { it.nextSibling }
            .filter { it.node.elementType == ScnTokens.WORD || it.node.elementType == ScnTokens.TARGET }
            .map { Word(it.text, it.textRange, it.node.elementType == ScnTokens.TARGET) }.toList()

    val lineText: String get() = text.trimEnd().removePrefix("###").trim()
}

class ScnDirective(node: ASTNode) : ASTWrapperPsiElement(node) {
    private val match: java.util.regex.Matcher? get() = ScnLexer.DIRECTIVE.matcher(text.trimEnd()).takeIf { it.matches() }

    val key: String get() = match?.group(1) ?: ""
    val value: String get() = match?.group(2) ?: ""
    val line: Int get() = (containingFile as ScnFile).lineOf(textRange.startOffset)

    companion object {
        /** `x` of `x = value` (the `@var` and `@capture` forms), else null. */
        fun assignedName(value: String): String? = ASSIGN.matchEntire(value)?.groupValues?.get(1)
        private val ASSIGN = Regex("([A-Za-z_][A-Za-z0-9_.-]*)\\s*=.*", RegexOption.DOT_MATCHES_ALL)
    }
}

class ScnBody(node: ASTNode) : ASTWrapperPsiElement(node)
