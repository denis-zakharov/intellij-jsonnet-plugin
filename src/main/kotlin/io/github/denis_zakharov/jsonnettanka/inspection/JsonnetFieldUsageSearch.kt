package io.github.denis_zakharov.jsonnettanka.inspection

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetFileType
import io.github.denis_zakharov.jsonnettanka.lang.LibsonnetFileType
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetImportExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetObjectLiteral
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetLookupElements
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil

/**
 * Project-wide "could anything read this field?" — the half of the unused-hidden-field check that
 * [JsonnetUnusedDeclarationUtil] (deliberately service-free, single object) can't do.
 *
 * Hidden fields are how Jsonnet libraries expose their API (`new():: ...`), so a field is only
 * safe to report when *no* same-named access anywhere in the project could refer to it. Our
 * resolution is best-effort (see `JsonnetStaticValues`), and "unknown" must not read as "unused", so:
 *  - `x.name` counts unless it positively resolves to a *different* object's field;
 *  - a string `'name'` counts (`o['name']`, `std.objectHasAll(o, 'name')`), except as a field name/import path;
 *  - comments, local variables and other declarations never count.
 *
 * Mixin fields (see [isMixin]) are the exception to "resolves to a different field": `withX():: { x+:: ... }`
 * is merged into whatever object declares the base `x::`, so a `self.x` that resolves to the base *reads* the
 * mixin's contribution too — and a `logging:: null` inside a merged value is there for its visibility
 * effect, not to be read. For those, any same-named read or same-named declaration elsewhere counts.
 */
object JsonnetFieldUsageSearch {

    fun isPossiblyUsed(field: JsonnetField): Boolean {
        val name = JsonnetResolver.fieldNameText(field) ?: return true
        // `'foo-bar'` etc. can't be found by a word search; don't guess.
        if (!JsonnetLookupElements.isPlainIdentifier(name)) return true

        val project = field.project
        val mixin = isMixin(field)
        val scope = GlobalSearchScope.getScopeRestrictedByFileTypes(
            GlobalSearchScope.projectScope(project), JsonnetFileType, LibsonnetFileType,
        )
        var used = false
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            { element, _ ->
                // The processor is also offered every ancestor of the hit; only the token itself matters.
                if (element.node?.firstChildNode == null && isUse(element, field, name, mixin)) used = true
                !used
            },
            scope, name, UsageSearchContext.ANY, true,
        )
        return used
    }

    private fun isUse(token: PsiElement, field: JsonnetField, name: String, mixin: Boolean): Boolean {
        val parent = token.parent
        return when (token.node.elementType) {
            JsonnetTypes.IDENTIFIER -> when {
                parent is JsonnetDotSuffix -> mixin || couldSelect(parent, field)
                else -> mixin && isOtherDeclaration(parent, field)
            }
            JsonnetTypes.STRING -> when {
                token.text.trim('\'', '"') != name -> false
                parent is JsonnetField -> mixin && isOtherDeclaration(parent, field)
                else -> parent !is JsonnetImportExpr
            }
            else -> false
        }
    }

    private fun isOtherDeclaration(parent: PsiElement, field: JsonnetField): Boolean =
        parent is JsonnetField && parent != field

    /**
     * Whether [field] is merged into a same-named field defined elsewhere: it uses `+::`/`+:::`, or sits in an
     * object literal that is the value of a `+:`-family field (`c+:: { logging:: null }`).
     */
    internal fun isMixin(field: JsonnetField): Boolean {
        if (field.node.findChildByType(MERGE_OPS) != null) return true
        val literal = PsiTreeUtil.getParentOfType(field, JsonnetObjectLiteral::class.java) ?: return false
        val owner = (literal.parent as? JsonnetExpr)?.parent as? JsonnetField ?: return false
        return owner.node.findChildByType(MERGE_OPS) != null
    }

    private val MERGE_OPS = TokenSet.create(
        JsonnetTypes.PLUSCOLON, JsonnetTypes.PLUSCOLONCOLON, JsonnetTypes.PLUSCOLONCOLONCOLON,
    )

    private fun couldSelect(suffix: JsonnetDotSuffix, field: JsonnetField): Boolean {
        val targets = (suffix.reference as? PsiPolyVariantReference)?.multiResolve(false) ?: return true
        return targets.isEmpty() || targets.any { it.element == field }
    }
}
