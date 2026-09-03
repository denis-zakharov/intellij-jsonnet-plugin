package com.dz.intellijjsonnet.inspection

import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetLocalExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.JsonnetObjectLiteral
import com.dz.intellijjsonnet.lang.psi.JsonnetObjectLocal
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Phase 5's "dead-code detection (unused `local`s/fields)" item. A `local`
 * binding is never visible outside the scope it's declared in — Jsonnet's
 * `import` only ever returns a file's final expression *value*, never its
 * internal bindings — so "does anything in this scope reference it" is exact,
 * not an approximation, for `local`s of either kind (expression-level and
 * object-level).
 *
 * Object *fields* are different: a plain (`:`/`+:`) field IS the object's
 * exported/serialized output — "nothing reads it via `self`/`$`" doesn't mean
 * it's dead, it may well be the whole point of the file (e.g. a
 * `local Foo = { a: 1, b: 2 }; Foo`-shaped library). Only *hidden*
 * (`::`/`+::`/`:::`) fields are Jsonnet's actual "private helper" mechanism,
 * so only those are checked here — see [JsonnetResolver.isHiddenField].
 */
object JsonnetUnusedDeclarationUtil {

    fun isUnused(bind: JsonnetBind): Boolean {
        val name = bind.nameIdentifier?.text ?: return false
        val scope = searchScopeFor(bind) ?: return false
        return !isReferenced(scope, bind)
    }

    fun isUnusedHiddenField(field: JsonnetField): Boolean {
        if (!JsonnetResolver.isHiddenField(field)) return false
        JsonnetResolver.fieldNameText(field) ?: return false
        val scope = PsiTreeUtil.getParentOfType(field, JsonnetObjectLiteral::class.java) ?: return false
        return !isReferenced(scope, field)
    }

    /** Every `local` group (expr-level or object-level) is visible to its own siblings and body — see rule above. */
    private fun searchScopeFor(bind: JsonnetBind): PsiElement? = when (val parent = bind.parent) {
        is JsonnetLocalExpr -> parent
        is JsonnetObjectLocal -> PsiTreeUtil.getParentOfType(parent, JsonnetObjectLiteral::class.java)
        else -> null
    }

    private fun isReferenced(scope: PsiElement, declaration: PsiElement): Boolean {
        val nameRefs = PsiTreeUtil.findChildrenOfType(scope, JsonnetNameRef::class.java)
        val dotSuffixes = PsiTreeUtil.findChildrenOfType(scope, JsonnetDotSuffix::class.java)
        return nameRefs.any { it.reference?.resolve() == declaration } ||
            dotSuffixes.any { it.reference?.resolve() == declaration }
    }
}
