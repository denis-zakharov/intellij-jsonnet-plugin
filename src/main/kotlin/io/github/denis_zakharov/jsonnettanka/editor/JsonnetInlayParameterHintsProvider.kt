package io.github.denis_zakharov.jsonnettanka.editor

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetCallSuffix
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNamedArg
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetParamList
import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetFieldReference
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetLocalReference
import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
class JsonnetInlayParameterHintsProvider : InlayHintsProvider {

    /** A `name:` hint to show at [offset]. */
    data class ParameterHint(val text: String, val offset: Int)

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = object : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            for (hint in parameterHints(element)) {
                sink.addPresentation(
                    InlineInlayPosition(hint.offset, relatedToPrevious = false),
                    hintFormat = HintFormat.default.withColorKind(HintColorKind.Parameter),
                ) { text(hint.text) }
            }
        }
    }

    fun parameterHints(element: PsiElement): List<ParameterHint> {
        val callSuffix = element as? JsonnetCallSuffix ?: return emptyList()
        val argList = callSuffix.argList ?: return emptyList()
        val paramNames = paramNamesFor(callSuffix) ?: return emptyList()
        if (paramNames.isEmpty()) return emptyList()

        val hints = mutableListOf<ParameterHint>()
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
                hints += ParameterHint("$paramName:", arg.textRange.startOffset)
            }
            position++
        }
        return hints
    }

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
