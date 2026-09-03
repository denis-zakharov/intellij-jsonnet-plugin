package com.dz.intellijjsonnet.lang.psi.reference

import com.dz.intellijjsonnet.lang.psi.JsonnetArrayComprehension
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetFunctionExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetLocalExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetObjectComprehension
import com.dz.intellijjsonnet.lang.psi.JsonnetObjectLiteral
import com.dz.intellijjsonnet.lang.psi.JsonnetParamList
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Static-scope resolution for plain identifier references (`local`/function
 * params/comprehension loop variables). Deliberately simple: walks the PSI
 * parent chain and checks each lexical scope in turn — good enough for
 * go-to-definition, not a full binder (doesn't model shadowing edge cases
 * beyond "nearest enclosing scope wins", which matches Jsonnet's own rules).
 */
object JsonnetResolver {

    fun resolveLocalName(from: PsiElement, name: String): PsiElement? {
        var context: PsiElement? = from
        while (context != null) {
            val parent = context.parent
            when (parent) {
                is JsonnetLocalExpr -> {
                    parent.bindList.firstOrNull { it.nameIdentifier?.text == name }?.let { return it }
                }
                is JsonnetParamList -> {
                    parent.paramList.firstOrNull { it.nameIdentifier?.text == name }?.let { return it }
                }
                is JsonnetFunctionExpr -> {
                    parent.paramList?.paramList?.firstOrNull { it.nameIdentifier?.text == name }?.let { return it }
                }
                is JsonnetField -> {
                    parent.paramList?.paramList?.firstOrNull { it.nameIdentifier?.text == name }?.let { return it }
                }
                is JsonnetArrayComprehension -> {
                    parent.forSpecList.firstOrNull { it.nameIdentifier?.text == name }?.let { return it }
                }
                is JsonnetObjectComprehension -> {
                    parent.forSpecList.firstOrNull { it.nameIdentifier?.text == name }?.let { return it }
                    parent.objectLocalList.firstOrNull { it.bind?.nameIdentifier?.text == name }?.bind?.let { return it }
                }
                is JsonnetObjectLiteral -> {
                    parent.objectMemberList?.objectLocalList
                        ?.firstOrNull { it.bind?.nameIdentifier?.text == name }
                        ?.bind
                        ?.let { return it }
                }
                else -> {}
            }
            context = parent
        }
        return null
    }

    /** Direct field declarations (not comprehensions, not inherited via `+`) of [obj]. */
    fun directFields(obj: JsonnetObjectLiteral): List<JsonnetField> =
        obj.objectMemberList?.fieldList.orEmpty()

    fun fieldNameText(field: JsonnetField): String? {
        val first = field.node.firstChildNode ?: return null
        return when (first.elementType) {
            JsonnetTypes.IDENTIFIER -> first.text
            JsonnetTypes.STRING -> unquote(first.text)
            else -> null
        }
    }

    /** `::`/`+::`/`:::` — a Jsonnet-idiomatic "private" field that never contributes to the object's rendered JSON. */
    fun isHiddenField(field: JsonnetField): Boolean = field.node.findChildByType(HIDDEN_FIELD_OPS) != null

    private val HIDDEN_FIELD_OPS = com.intellij.psi.tree.TokenSet.create(
        JsonnetTypes.COLONCOLON, JsonnetTypes.COLONCOLONCOLON,
        JsonnetTypes.PLUSCOLONCOLON, JsonnetTypes.PLUSCOLONCOLONCOLON,
    )

    private fun unquote(text: String): String {
        if (text.length >= 2 && (text.startsWith("\"") || text.startsWith("'"))) {
            return text.substring(1, text.length - 1)
        }
        return text
    }

    /** Nearest enclosing object literal, for resolving `self.foo`. */
    fun enclosingObjectLiteral(from: PsiElement): JsonnetObjectLiteral? =
        PsiTreeUtil.getParentOfType(from, JsonnetObjectLiteral::class.java)

    /** The document's root object literal (unwrapping leading top-level `local`s), for `$.foo`. */
    fun rootObjectLiteral(from: PsiElement): JsonnetObjectLiteral? {
        val file = from.containingFile ?: return null
        var expr = PsiTreeUtil.findChildOfType(file, JsonnetExpr::class.java) ?: return null
        while (true) {
            val local = PsiTreeUtil.getChildOfType(expr, JsonnetLocalExpr::class.java) ?: break
            expr = local.expr ?: return null
        }
        return PsiTreeUtil.getChildOfType(expr, JsonnetObjectLiteral::class.java)
    }
}
