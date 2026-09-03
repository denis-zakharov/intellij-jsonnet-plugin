package com.dz.intellijjsonnet.lang.psi.reference

import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil

/**
 * Resolves the narrow, statically-decidable case of `self.foo` / `$.foo` (this
 * [dotSuffix] is the first/only suffix directly after a bare `self`/`$`) to the
 * matching field declared directly in the relevant object literal. Anything
 * requiring type-directed lookup (chained access, `super.foo`, fields reached
 * through composition/imports) is out of scope for this phase.
 */
class JsonnetFieldReference(element: JsonnetDotSuffix) : PsiPolyVariantReferenceBase<JsonnetDotSuffix>(element) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val name = element.nameIdentifier?.text ?: return ResolveResult.EMPTY_ARRAY
        val receiver = receiverKeyword() ?: return ResolveResult.EMPTY_ARRAY
        val obj = when (receiver) {
            JsonnetTypes.SELF_KW -> JsonnetResolver.enclosingObjectLiteral(element)
            JsonnetTypes.DOLLAR -> JsonnetResolver.rootObjectLiteral(element)
            else -> null
        } ?: return ResolveResult.EMPTY_ARRAY

        val field = JsonnetResolver.directFields(obj).firstOrNull { JsonnetResolver.fieldNameText(it) == name }
            ?: return ResolveResult.EMPTY_ARRAY
        return arrayOf(PsiElementResolveResult(field))
    }

    /** The `self`/`$` token immediately preceding this suffix in its Expr, if any. */
    private fun receiverKeyword(): com.intellij.psi.tree.IElementType? {
        val exprParent = element.parent as? JsonnetExpr ?: return null
        var prev: PsiElement? = element.prevSibling
        while (prev != null && prev.node.elementType == com.intellij.psi.TokenType.WHITE_SPACE) {
            prev = prev.prevSibling
        }
        if (prev == null || prev.parent != exprParent) return null
        val type = prev.node.elementType
        return if (type == JsonnetTypes.SELF_KW || type == JsonnetTypes.DOLLAR) type else null
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
