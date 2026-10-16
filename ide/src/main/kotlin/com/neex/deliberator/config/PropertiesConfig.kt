package com.neex.deliberator.config

import com.intellij.lang.properties.psi.impl.PropertyValueImpl
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext

/** `${X}` in the value of a property of `application*.properties` (loaded only with the Properties plugin: `deliberator-properties.xml`). */
class PropertiesConfigReferences : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PropertyValueImpl::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> = placeholderReferences(element)
        })
    }
}
