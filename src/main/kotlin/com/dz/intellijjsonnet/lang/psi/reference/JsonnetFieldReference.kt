package com.dz.intellijjsonnet.lang.psi.reference

import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetElementFactory
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache

/**
 * Resolves `receiver.foo` to the field(s) named `foo` declared in whatever object(s) the receiver
 * statically is — see [JsonnetStaticValues] for what "statically" covers (locals, imports, `self`/`super`/`$`,
 * `+`/`{...}` composition, calls of source-defined functions). Several results are possible
 * (`(a + b).foo` where both declare it), hence poly-variant.
 */
class JsonnetFieldReference(element: JsonnetDotSuffix) : PsiPolyVariantReferenceBase<JsonnetDotSuffix>(element) {

    // Cached because annotators, inlay hints and inspections all resolve the same suffixes, and a
    // resolve can walk through several imported files. The light `ParsingTestCase` project has no
    // ResolveCache service (see AGENTS.md), hence the uncached fallback.
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project)?.resolveWithCaching(this, Resolver, false, incompleteCode)
            ?: resolveUncached()

    private fun resolveUncached(): Array<ResolveResult> {
        val name = element.nameIdentifier?.text ?: return ResolveResult.EMPTY_ARRAY
        return JsonnetStaticValues.receiverOf(element).fieldsNamed(name)
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }

    private object Resolver : ResolveCache.PolyVariantResolver<JsonnetFieldReference> {
        override fun resolve(ref: JsonnetFieldReference, incompleteCode: Boolean): Array<ResolveResult> =
            ref.resolveUncached()
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        element.nameIdentifier?.replace(JsonnetElementFactory.createIdentifierLeaf(element.project, newElementName))
        return element
    }

    /** Field names of the receiver, for completion — `std.` and `tk.` members come from their own contributors. */
    override fun getVariants(): Array<Any> {
        val seen = HashSet<String>()
        return JsonnetStaticValues.receiverOf(element).fields
            .filter { field ->
                val name = JsonnetResolver.fieldNameText(field)
                name != null && JsonnetLookupElements.isPlainIdentifier(name) && seen.add(name)
            }
            .map { JsonnetLookupElements.forField(it) }
            .toTypedArray()
    }

    // No ElementManipulator is registered for JsonnetDotSuffixImpl, and the
    // default range (the whole `.foo` suffix, including the dot) would be
    // wrong anyway — see the matching note in JsonnetLocalReference.
    override fun getRangeInElement(): TextRange {
        val id = element.nameIdentifier ?: return super.getRangeInElement()
        return id.textRange.shiftLeft(element.textRange.startOffset)
    }
}
