package com.neex.tellij.java.endpoint

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiUtil
import com.neex.tellij.spi.StepError

/**
 * From an implementation method to what it exposes. The chain is the method plus every method
 * it overrides (the interface with the annotation wall); the first mapping found wins, docs come
 * from wherever they are. Must be called inside a read action.
 */
object EndpointResolver {

    fun resolve(method: PsiMethod): Endpoint? {
        val chain = chainOf(method)
        val mapping = chain.firstNotNullOfOrNull { m -> mappingOf(m) } ?: return null
        val (httpMethod, methodPath) = mapping
        val prefix = chain.firstNotNullOfOrNull { m -> m.containingClass?.let { c -> Annos.find(c, Annos.REQUEST_MAPPING)?.let { Annos.str(it, "value", "path") ?: "" } } } ?: ""
        val path = joinPath(prefix, methodPath)

        val operation = chain.firstNotNullOfOrNull { Annos.find(it, Annos.OPERATION) }
        val tags = chain.mapNotNull { it.containingClass }.flatMap { c -> Annos.all(c, Annos.TAG).mapNotNull { Annos.str(it, "name") } }.distinct()

        val params = ArrayList<Param>()
        var body: DtoShape? = null
        method.parameterList.parameters.forEachIndexed { i, p ->
            val owners = chain.mapNotNull { it.parameterList.parameters.getOrNull(i) }
            paramOf(p, owners)?.let { param ->
                params += param
                if (param.where == ParamWhere.BODY) body = DtoShapeWalker.walk(p.type)
            }
        }

        val responses = responsesOf(chain, method)
        val impl = methodRef(method, chain)
        return Endpoint(httpMethod, path, Annos.str(operation, "summary"), Annos.str(operation, "description"), tags, params, body, responses, impl)
    }

    /** `OrdersController.create` or `com.acme.OrdersController.create`; [signature] picks an overload. */
    fun findMethod(project: Project, ref: String, signature: String?): PsiMethod {
        if (DumbService.isDumb(project)) throw StepError("indexing in progress", "wait for the index and run again")
        val dot = ref.lastIndexOf('.')
        if (dot <= 0) throw StepError("controller reference '$ref' must be Class.method", "### controller OrdersController.create")
        val className = ref.substring(0, dot)
        val methodName = ref.substring(dot + 1)
        val scope = GlobalSearchScope.projectScope(project)
        val classes: List<PsiClass> = try {
            if (className.contains('.')) listOfNotNull(JavaPsiFacade.getInstance(project).findClass(className, scope))
            else PsiShortNamesCache.getInstance(project).getClassesByName(className, scope).toList()
        } catch (e: IndexNotReadyException) {
            throw StepError("indexing in progress", "wait for the index and run again")
        }
        val cls = when (classes.size) {
            0 -> throw StepError("no class '$className' in the project", "use the qualified name, e.g. com.acme.api.$className")
            1 -> classes[0]
            else -> throw StepError("several classes named '$className'", "use the qualified name: ${classes.mapNotNull { it.qualifiedName }.joinToString(", ")}")
        }
        val methods = cls.findMethodsByName(methodName, false).toList()
        if (methods.isEmpty()) throw StepError("no method '$methodName' in ${cls.qualifiedName}", "methods: ${cls.methods.map { it.name }.distinct().joinToString(", ")}")
        if (methods.size == 1) return methods[0]
        val sig = signature?.trim() ?: throw StepError("$className.$methodName is overloaded", "pick one: # @method ${methods.joinToString(" | ") { signatureOf(it) }}")
        return methods.firstOrNull { signatureOf(it) == sig || signatureOf(it).substringAfter('(') == sig.substringAfter('(') }
            ?: throw StepError("no overload '$sig'", "one of: ${methods.joinToString(" | ") { signatureOf(it) }}")
    }

    fun signatureOf(m: PsiMethod): String = "${m.name}(${m.parameterList.parameters.joinToString(", ") { it.type.presentableText }})"

    private fun chainOf(method: PsiMethod): List<PsiMethod> {
        val out = ArrayList<PsiMethod>()
        val queue = ArrayDeque(listOf(method))
        while (queue.isNotEmpty() && out.size < 20) {
            val m = queue.removeFirst()
            if (out.any { it === m }) continue
            out += m
            queue += m.findSuperMethods()
        }
        return out
    }

    private fun mappingOf(m: PsiMethod): Pair<String, String>? {
        for ((fqn, http) in Annos.MAPPINGS) {
            Annos.find(m, fqn)?.let { return http to (Annos.str(it, "value", "path") ?: "") }
        }
        Annos.find(m, Annos.REQUEST_MAPPING)?.let { return (Annos.enumNames(it, "method").firstOrNull() ?: "GET") to (Annos.str(it, "value", "path") ?: "") }
        return null
    }

    fun joinPath(prefix: String, path: String): String {
        val p = "/" + listOf(prefix, path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
        return p
    }

    private val FRAMEWORK_TYPES = setOf("HttpServletRequest", "HttpServletResponse", "HttpSession", "Principal", "Model", "ModelMap", "Authentication", "Locale", "Errors", "BindingResult", "UriComponentsBuilder")

    private fun paramOf(p: PsiParameter, owners: List<PsiParameter>): Param? {
        val typeText = p.type.presentableText
        if (typeText.substringBefore('<') in FRAMEWORK_TYPES) return null
        val doc = owners.firstNotNullOfOrNull { Annos.find(it, Annos.PARAMETER) }
        val description = Annos.str(doc, "description") ?: owners.firstNotNullOfOrNull { Annos.str(Annos.find(it, Annos.OAS_REQUEST_BODY), "description") }
        owners.firstNotNullOfOrNull { Annos.find(it, Annos.PATH_VARIABLE) }?.let {
            return Param(Annos.str(it, "name", "value") ?: p.name, ParamWhere.PATH, typeText, Annos.bool(it, "required") ?: true, null, description)
        }
        owners.firstNotNullOfOrNull { Annos.find(it, Annos.REQUEST_PARAM) }?.let {
            val default = Annos.str(it, "defaultValue")
            return Param(Annos.str(it, "name", "value") ?: p.name, ParamWhere.QUERY, typeText, default == null && (Annos.bool(it, "required") ?: true), default, description)
        }
        owners.firstNotNullOfOrNull { Annos.find(it, Annos.REQUEST_HEADER) }?.let {
            val default = Annos.str(it, "defaultValue")
            return Param(Annos.str(it, "name", "value") ?: p.name, ParamWhere.HEADER, typeText, default == null && (Annos.bool(it, "required") ?: true), default, description)
        }
        owners.firstNotNullOfOrNull { Annos.find(it, Annos.REQUEST_BODY) }?.let {
            return Param(p.name, ParamWhere.BODY, typeText, Annos.bool(it, "required") ?: true, null, description)
        }
        // Spring binds unannotated simple types from the query string.
        return if (isSimple(p.type)) Param(p.name, ParamWhere.QUERY, typeText, false, null, description) else null
    }

    private fun isSimple(t: PsiType): Boolean {
        if (t is com.intellij.psi.PsiPrimitiveType) return true
        val fqn = PsiUtil.resolveClassInClassTypeOnly(t)?.qualifiedName ?: return false
        return fqn.startsWith("java.lang.") || fqn in setOf("java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID", "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime") ||
            PsiUtil.resolveClassInClassTypeOnly(t)?.isEnum == true
    }

    private val STATUS_NAMES = mapOf("OK" to 200, "CREATED" to 201, "ACCEPTED" to 202, "NO_CONTENT" to 204, "BAD_REQUEST" to 400, "NOT_FOUND" to 404, "CONFLICT" to 409)

    private fun responsesOf(chain: List<PsiMethod>, method: PsiMethod): List<Response> {
        val out = ArrayList<Response>()
        for (m in chain) {
            val annos = Annos.nested(Annos.find(m, Annos.API_RESPONSES), "value") + Annos.all(m, Annos.API_RESPONSE)
            for (a in annos) {
                val code = Annos.str(a, "responseCode")?.toIntOrNull() ?: 200
                val type = Annos.nested(a, "content").firstNotNullOfOrNull { c -> Annos.classRef(Annos.nested(c, "schema").firstOrNull(), "implementation")?.name }
                if (out.none { it.status == code }) out += Response(code, Annos.str(a, "description"), type)
            }
            if (out.isNotEmpty()) break
        }
        if (out.none { it.status in 200..299 }) {
            val status = chain.firstNotNullOfOrNull { Annos.find(it, Annos.RESPONSE_STATUS) }?.let { Annos.enumNames(it, "value", ).firstOrNull() ?: Annos.enumNames(it, "code").firstOrNull() }?.let { STATUS_NAMES[it] }
            val rt = method.returnType
            val (code, type) = when {
                rt == null || rt == PsiTypes.voidType() -> (status ?: 204) to null
                else -> (status ?: 200) to returnTypeText(rt)
            }
            out.add(0, Response(code, null, type))
        }
        return out.sortedBy { it.status }
    }

    private fun returnTypeText(t: PsiType): String {
        val ct = t as? PsiClassType ?: return t.presentableText
        val fqn = ct.resolve()?.qualifiedName
        if ((fqn == "org.springframework.http.ResponseEntity" || fqn == "java.util.concurrent.CompletableFuture" || fqn == "reactor.core.publisher.Mono") && ct.parameters.size == 1) return ct.parameters[0].presentableText
        return t.presentableText
    }

    private fun methodRef(method: PsiMethod, chain: List<PsiMethod>): MethodRef {
        val file = method.containingFile
        val doc = PsiDocumentManager.getInstance(method.project).getDocument(file)
        fun lineOf(offset: Int) = doc?.getLineNumber(offset) ?: 0
        val firstStatement = method.body?.statements?.firstOrNull()?.textOffset ?: method.body?.lBrace?.textOffset ?: method.textOffset
        val declaredIn = chain.drop(1).firstNotNullOfOrNull { it.containingClass?.name }
        return MethodRef(
            classFqn = method.containingClass?.qualifiedName ?: "?",
            methodName = method.name,
            declaredIn = declaredIn,
            fileUrl = file.virtualFile?.url,
            line = lineOf(method.nameIdentifier?.textOffset ?: method.textOffset),
            firstStatementLine = lineOf(firstStatement),
        )
    }
}
