package com.dz.intellijjsonnet.lang.psi.reference

import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult

/** Resolves a bare identifier reference to its `local`/param/comprehension-loop-var declaration. */
class JsonnetLocalReference(element: JsonnetNameRef) : PsiPolyVariantReferenceBase<JsonnetNameRef>(element) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val name = element.nameIdentifier?.text ?: return ResolveResult.EMPTY_ARRAY
        val target = JsonnetResolver.resolveLocalName(element, name) ?: return ResolveResult.EMPTY_ARRAY
        return arrayOf(PsiElementResolveResult(target))
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
