package com.dz.intellijjsonnet.editor

import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetCallSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.JsonnetNamedArg
import com.dz.intellijjsonnet.lang.psi.JsonnetParamList
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetFieldReference
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetLocalReference
import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.InlayParameterHintsProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType

/**
 * Phase 5's "named-argument hints on function calls" inlay-hints item —
 * `f(1, 2)` for `local f(x, y) = ...;` shows `x: 1, y: 2`. Resolves the
 * callee through the same Phase 1/2 reference machinery used for go-to-
 * definition ([JsonnetLocalReference] for a bare `f(...)` call,
 * [JsonnetFieldReference] for a `self.method(...)`/`obj.method(...)`-style
 * one), so it only fires where go-to-definition would also work — no
 * separate type-inference layer. The plan's other Phase 5 inlay-hints
 * item, "inferred merge-result hints on composed objects", is *not*
 * implemented: statically inferring the resulting field set of a `+` merge
 * without evaluating both sides isn't precise enough to be non-misleading
 * once computed field names/imports are involved, and the two-tier plan
 * already has a precise answer for "what does this evaluate to" — the
 * Preview tool window.
 */
class JsonnetInlayParameterHintsProvider : InlayParameterHintsProvider {

    override fun getParameterHints(element: PsiElement): List<InlayInfo> {
        val callSuffix = element as? JsonnetCallSuffix ?: return emptyList()
        val argList = callSuffix.argList ?: return emptyList()
        val paramNames = paramNamesFor(callSuffix) ?: return emptyList()
        if (paramNames.isEmpty()) return emptyList()

        val hints = mutableListOf<InlayInfo>()
        var position = 0
        for (child in argList.node.getChildren(null)) {
            val arg = child.psi
            if (arg is JsonnetNamedArg) {
                position++
                continue
            }
            if (arg !is JsonnetExpr) continue
            val paramName = paramNames.getOrNull(position)
            if (paramName != null && arg.text != paramName) {
                hints += InlayInfo("$paramName:", arg.textRange.startOffset)
            }
            position++
        }
        return hints
    }

    override fun getDefaultBlackList(): Set<String> = emptySet()

    /** The names of the resolved callee's declared params, in order — `null` if the callee can't be resolved. */
    private fun paramNamesFor(callSuffix: JsonnetCallSuffix): List<String>? {
        val exprParent = callSuffix.parent ?: return null
        var prev: PsiElement? = callSuffix.prevSibling
        while (prev != null && prev.node.elementType == TokenType.WHITE_SPACE) {
            prev = prev.prevSibling
        }
        if (prev == null || prev.parent != exprParent) return null

        val paramList: JsonnetParamList? = when (prev) {
            is JsonnetNameRef -> {
                val target = (prev.reference as? JsonnetLocalReference)?.resolve()
                (target as? JsonnetBind)?.paramList
            }
            is JsonnetDotSuffix -> {
                val target = (prev.reference as? JsonnetFieldReference)?.resolve()
                (target as? JsonnetField)?.paramList
            }
            else -> null
        }
        return paramList?.paramList?.map { it.nameIdentifier?.text ?: return null }
    }
}
