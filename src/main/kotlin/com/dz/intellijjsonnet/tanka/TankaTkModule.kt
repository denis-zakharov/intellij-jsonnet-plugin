package com.dz.intellijjsonnet.tanka

import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetImportExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetLocalReference
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil

/**
 * `import 'tk'` isn't a real file — Tanka injects environment metadata at
 * eval time (`tk.env.spec.namespace` etc., see plan §5) — so it needs
 * explicit handling or it just looks like a broken import. This models only
 * the common, idiomatic shape (`local tk = import 'tk'; tk.env.spec.*`)
 * rather than general type inference: enough for the false-positive fix and
 * for completion, not a substitute for a real type system.
 */
object TankaTkModule {

    const val VIRTUAL_MODULE_NAME = "tk"

    val envFields = listOf("metadata", "spec")
    val specFields = listOf(
        "apiServer", "namespace", "contextNames", "resourceDefaults",
        "injectLabels", "expectVersions", "applyStrategy",
    )

    fun isTkImportString(importExpr: JsonnetImportExpr, stringText: String): Boolean =
        stringText.trim('\'', '"') == VIRTUAL_MODULE_NAME

    fun isTkImportPath(text: String): Boolean = text.trim('\'', '"') == VIRTUAL_MODULE_NAME

    /**
     * For `<something>.a.b.<caret>`, returns the dot-segment names leading up to
     * (not including) [dotSuffix] if the chain's base identifier resolves to a
     * `local` bound directly to `import 'tk'` — null otherwise.
     */
    fun tkAccessChainBefore(dotSuffix: JsonnetDotSuffix): List<String>? {
        val exprParent = dotSuffix.parent as? JsonnetExpr ?: return null
        val segments = mutableListOf<String>()
        var current: PsiElement? = prevNonWhitespace(dotSuffix)
        while (current is JsonnetDotSuffix) {
            if (current.parent != exprParent) return null
            segments.add(0, current.nameText() ?: return null)
            current = prevNonWhitespace(current)
        }
        val base = current as? JsonnetNameRef ?: return null
        if (base.parent != exprParent) return null
        if (!resolvesToTkImport(base)) return null
        return segments
    }

    private fun resolvesToTkImport(nameRef: JsonnetNameRef): Boolean {
        val reference = nameRef.reference as? JsonnetLocalReference ?: return false
        val bind = reference.resolve() as? JsonnetBind ?: return false
        val importExpr = PsiTreeUtil.getChildOfType(bind.expr, JsonnetImportExpr::class.java) ?: return false
        val stringChild = importExpr.node.findChildByType(JsonnetTypes.STRING)?.psi ?: return false
        return isTkImportString(importExpr, stringChild.text)
    }

    private fun JsonnetDotSuffix.nameText(): String? = nameIdentifierText(this)

    private fun nameIdentifierText(dotSuffix: JsonnetDotSuffix): String? =
        dotSuffix.node.findChildByType(JsonnetTypes.IDENTIFIER)?.text

    private fun prevNonWhitespace(element: PsiElement): PsiElement? {
        var prev = element.prevSibling
        while (prev != null && prev.node.elementType == TokenType.WHITE_SPACE) {
            prev = prev.prevSibling
        }
        return prev
    }
}
