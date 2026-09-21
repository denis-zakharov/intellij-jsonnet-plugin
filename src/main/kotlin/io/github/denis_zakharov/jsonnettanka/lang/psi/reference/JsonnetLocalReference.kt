package io.github.denis_zakharov.jsonnettanka.lang.psi.reference

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetElementFactory
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef
import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult

/**
 * Resolves a bare identifier reference to its `local`/param/comprehension-loop-var
 * declaration. `std` is the one implicit global Jsonnet has, so it's excluded via
 * [isSoft] — [io.github.denis_zakharov.jsonnettanka.editor.JsonnetUnresolvedReferenceAnnotator]
 * uses that to skip it when flagging genuinely unresolved names.
 */
class JsonnetLocalReference(element: JsonnetNameRef) : PsiPolyVariantReferenceBase<JsonnetNameRef>(element) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val name = element.nameIdentifier?.text ?: return ResolveResult.EMPTY_ARRAY
        val target = JsonnetResolver.resolveLocalName(element, name) ?: return ResolveResult.EMPTY_ARRAY
        return arrayOf(PsiElementResolveResult(target))
    }

    override fun isSoft(): Boolean = element.nameIdentifier?.text == "std"

    // No ElementManipulator is registered for JsonnetNameRefImpl, so the
    // PsiReferenceBase default (which needs one to compute this) throws —
    // only surfaces via text-based reference search (rename's "other
    // usages" pass, Find Usages), not simple resolve(), which is why this
    // went unnoticed until BasePlatformTestCase-based rename tests existed.
    override fun getRangeInElement(): TextRange {
        val id = element.nameIdentifier ?: return super.getRangeInElement()
        return id.textRange.shiftLeft(element.textRange.startOffset)
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        element.nameIdentifier?.replace(JsonnetElementFactory.createIdentifierLeaf(element.project, newElementName))
        return element
    }

    /** Every local/param/loop variable visible here; keywords and `std` come from [JsonnetKeywordCompletionContributor]. */
    override fun getVariants(): Array<Any> =
        JsonnetResolver.visibleDeclarations(element).mapNotNull { JsonnetLookupElements.forDeclaration(it) }.toTypedArray()
}
