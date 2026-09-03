package com.dz.intellijjsonnet.lang.psi.reference

import com.dz.intellijjsonnet.lang.psi.JsonnetImportExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.util.ProcessingContext

/** `import`/`importstr`/`importbin` path strings resolve as relative file references. */
class JsonnetImportReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JsonnetTypes.STRING).withParent(JsonnetImportExpr::class.java),
            ImportPathReferenceProvider,
        )
    }
}

private object ImportPathReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val text = element.text
        if (text.length < 2) return PsiReference.EMPTY_ARRAY
        val path = text.substring(1, text.length - 1)
        @Suppress("UNCHECKED_CAST")
        return FileReferenceSet(path, element, 1, null, true).allReferences as Array<PsiReference>
    }
}
