package com.neex.tellij.java.endpoint

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiReferenceExpression

/** Reading annotation attributes by FQN — the only Spring/OpenAPI knowledge the plugin has. */
object Annos {
    private const val SPRING = "org.springframework.web.bind.annotation."
    const val REQUEST_MAPPING = SPRING + "RequestMapping"
    val MAPPINGS: Map<String, String> = mapOf(
        SPRING + "GetMapping" to "GET", SPRING + "PostMapping" to "POST", SPRING + "PutMapping" to "PUT",
        SPRING + "PatchMapping" to "PATCH", SPRING + "DeleteMapping" to "DELETE",
    )
    const val PATH_VARIABLE = SPRING + "PathVariable"
    const val REQUEST_PARAM = SPRING + "RequestParam"
    const val REQUEST_HEADER = SPRING + "RequestHeader"
    const val REQUEST_BODY = SPRING + "RequestBody"
    const val RESPONSE_STATUS = SPRING + "ResponseStatus"

    private const val OAS = "io.swagger.v3.oas.annotations."
    const val OPERATION = OAS + "Operation"
    const val PARAMETER = OAS + "Parameter"
    const val TAG = OAS + "tags.Tag"
    const val API_RESPONSE = OAS + "responses.ApiResponse"
    const val API_RESPONSES = OAS + "responses.ApiResponses"
    const val CONTENT = OAS + "media.Content"
    const val SCHEMA = OAS + "media.Schema"
    const val OAS_REQUEST_BODY = OAS + "parameters.RequestBody"

    const val JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty"
    private const val VALIDATION = "jakarta.validation.constraints."
    val REQUIRED_CONSTRAINTS = setOf(VALIDATION + "NotNull", VALIDATION + "NotBlank", VALIDATION + "NotEmpty")

    /** Spring's marker for "no default": a string of control characters. */
    private fun isNone(s: String?) = s == null || s.any { it == '\uE000' || it == '\n' }

    fun find(owner: PsiModifierListOwner, vararg fqns: String): PsiAnnotation? = AnnotationUtil.findAnnotation(owner, *fqns)

    fun all(owner: PsiModifierListOwner, fqn: String): List<PsiAnnotation> = owner.annotations.filter { it.hasQualifiedName(fqn) }

    fun str(a: PsiAnnotation?, vararg attrs: String): String? {
        if (a == null) return null
        for (attr in attrs) {
            val v = strings(a, attr).firstOrNull { it.isNotEmpty() && !isNone(it) }
            if (v != null) return v
        }
        return null
    }

    fun strings(a: PsiAnnotation, attr: String): List<String> {
        val value = a.findAttributeValue(attr) ?: return emptyList()
        return values(value).mapNotNull { constant(it) }
    }

    fun bool(a: PsiAnnotation?, attr: String): Boolean? = a?.let { AnnotationUtil.getBooleanAttributeValue(it, attr) }

    fun enumNames(a: PsiAnnotation?, attr: String): List<String> {
        val value = a?.findAttributeValue(attr) ?: return emptyList()
        return values(value).mapNotNull { (it as? PsiReferenceExpression)?.referenceName }
    }

    fun classRef(a: PsiAnnotation?, attr: String): PsiClass? {
        val v = a?.findAttributeValue(attr) as? PsiClassObjectAccessExpression ?: return null
        return (v.operand.type as? PsiClassType)?.resolve()
    }

    fun nested(a: PsiAnnotation?, attr: String): List<PsiAnnotation> {
        val value = a?.findAttributeValue(attr) ?: return emptyList()
        return values(value).filterIsInstance<PsiAnnotation>()
    }

    private fun values(v: PsiAnnotationMemberValue): List<PsiAnnotationMemberValue> =
        if (v is PsiArrayInitializerMemberValue) v.initializers.toList() else listOf(v)

    private fun constant(v: PsiAnnotationMemberValue): String? {
        AnnotationUtil.getStringAttributeValue(v)?.let { return it }
        val e = v as? PsiExpression ?: return null
        return JavaPsiFacade.getInstance(e.project).constantEvaluationHelper.computeConstantExpression(e)?.toString()
    }
}
