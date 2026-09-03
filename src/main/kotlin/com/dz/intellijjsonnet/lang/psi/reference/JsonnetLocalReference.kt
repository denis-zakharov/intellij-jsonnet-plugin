package com.dz.intellijjsonnet.lang.psi.reference

import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult

/**
 * Resolves a bare identifier reference to its `local`/param/comprehension-loop-var
 * declaration. `std` is the one implicit global Jsonnet has, so it's excluded via
 * [isSoft] — [com.dz.intellijjsonnet.editor.JsonnetUnresolvedReferenceAnnotator]
 * uses that to skip it when flagging genuinely unresolved names.
 */
class JsonnetLocalReference(element: JsonnetNameRef) : PsiPolyVariantReferenceBase<JsonnetNameRef>(element) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val name = element.nameIdentifier?.text ?: return ResolveResult.EMPTY_ARRAY
        val target = JsonnetResolver.resolveLocalName(element, name) ?: return ResolveResult.EMPTY_ARRAY
        return arrayOf(PsiElementResolveResult(target))
    }

    override fun isSoft(): Boolean = element.nameIdentifier?.text == "std"

    override fun getVariants(): Array<Any> = emptyArray()
}
