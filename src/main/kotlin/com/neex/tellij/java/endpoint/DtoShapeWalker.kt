package com.neex.tellij.java.endpoint

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiType
import com.intellij.psi.util.PropertyUtilBase
import com.intellij.psi.util.PsiUtil

/**
 * The shape of a request/response class as a human reads it: field, type, required, constraints,
 * nested classes up to a small depth. Records, plain fields and getter-only beans all count.
 */
object DtoShapeWalker {
    private const val MAX_DEPTH = 3

    fun walk(type: PsiType, depth: Int = MAX_DEPTH, seen: Set<String> = emptySet()): DtoShape? {
        val cls = PsiUtil.resolveClassInClassTypeOnly(type) ?: return null
        val fqn = cls.qualifiedName ?: return null
        if (isLibrary(fqn) || cls.isEnum || cls.isInterface || depth <= 0 || fqn in seen) return null
        val fields = ArrayList<Field>()
        val names = HashSet<String>()
        fun add(owner: PsiModifierListOwner, name: String, t: PsiType, doc: PsiAnnotationHolder = PsiAnnotationHolder(owner)) {
            if (!names.add(name)) return
            val required = doc.annotations.any { it.qualifiedName in Annos.REQUIRED_CONSTRAINTS }
            val schema = Annos.find(owner, Annos.SCHEMA)
            fields += Field(
                name = Annos.str(Annos.find(owner, Annos.JSON_PROPERTY), "value") ?: name,
                type = typeText(t),
                required = required,
                constraints = constraints(owner),
                description = Annos.str(schema, "description")?.let { d -> Annos.str(schema, "example")?.let { "$d (e.g. $it)" } ?: d },
                nested = elementClassType(t)?.let { walk(it, depth - 1, seen + fqn) },
            )
        }
        if (cls.isRecord) {
            for (c in cls.recordComponents) add(c, c.name, c.type)
        } else {
            for (f in cls.allFields) {
                if (f.hasModifierProperty(PsiModifier.STATIC) || f.hasModifierProperty(PsiModifier.TRANSIENT)) continue
                if (isLibrary(f.containingClass?.qualifiedName ?: "")) continue
                add(f, f.name, f.type)
            }
            for ((name, getter) in PropertyUtilBase.getAllProperties(cls, false, true)) {
                val t = getter.returnType ?: continue
                if (isLibrary(getter.containingClass?.qualifiedName ?: "")) continue
                add(getter, name, t)
            }
        }
        return DtoShape(cls.name ?: fqn, fields)
    }

    class PsiAnnotationHolder(owner: PsiModifierListOwner) {
        val annotations = owner.annotations.toList()
    }

    fun isLibrary(fqn: String): Boolean =
        fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("jakarta.") || fqn.startsWith("kotlin.") || fqn.startsWith("org.springframework.")

    /** `List<Item>` → Item, `Item[]` → Item, `Map<K, V>` → V, `Optional<T>` → T; otherwise the type itself. */
    fun elementClassType(t: PsiType): PsiType? {
        if (t is PsiArrayType) return t.componentType
        val ct = t as? PsiClassType ?: return null
        // By name, not by resolution: the JDK may be absent (light test fixtures) and the answer is the same.
        val name = ct.resolve()?.qualifiedName ?: ct.className ?: return t
        val simple = name.substringAfterLast('.')
        val args = ct.parameters
        return when {
            simple in setOf("Map", "HashMap", "TreeMap", "LinkedHashMap", "ConcurrentHashMap") && args.size == 2 -> args[1]
            simple in setOf("List", "Set", "Collection", "Iterable", "ArrayList", "LinkedList", "HashSet", "Optional", "Stream") && args.size == 1 -> args[0]
            else -> t
        }
    }

    fun typeText(t: PsiType): String {
        val cls = PsiUtil.resolveClassInClassTypeOnly(t)
        if (cls != null && cls.isEnum) {
            val constants = cls.fields.filterIsInstance<PsiEnumConstant>().map { it.name }
            return "${cls.name}(${constants.joinToString("|")})"
        }
        return t.presentableText
    }

    fun constraints(owner: PsiModifierListOwner): List<String> = owner.annotations.mapNotNull { a ->
        when (a.qualifiedName?.substringAfterLast('.')) {
            "Min" -> "≥ ${Annos.str(a, "value")}"
            "Max" -> "≤ ${Annos.str(a, "value")}"
            "Positive" -> "> 0"
            "PositiveOrZero" -> "≥ 0"
            "Negative" -> "< 0"
            "Size" -> "size ${Annos.str(a, "min") ?: "0"}..${Annos.str(a, "max") ?: "∞"}"
            "Pattern" -> "matches ${Annos.str(a, "regexp")}"
            "Email" -> "email"
            "DecimalMin" -> "≥ ${Annos.str(a, "value")}"
            "DecimalMax" -> "≤ ${Annos.str(a, "value")}"
            else -> null
        }
    }
}
