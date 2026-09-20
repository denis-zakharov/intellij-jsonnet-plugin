package com.dz.intellijjsonnet.inspection

import com.dz.intellijjsonnet.lang.JsonnetFileType
import com.dz.intellijjsonnet.lang.LibsonnetFileType
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetImportExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetLookupElements
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

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
 */
object JsonnetFieldUsageSearch {

    fun isPossiblyUsed(field: JsonnetField): Boolean {
        val name = JsonnetResolver.fieldNameText(field) ?: return true
        // `'foo-bar'` etc. can't be found by a word search; don't guess.
        if (!JsonnetLookupElements.isPlainIdentifier(name)) return true

        val project = field.project
        val scope = GlobalSearchScope.getScopeRestrictedByFileTypes(
            GlobalSearchScope.projectScope(project), JsonnetFileType, LibsonnetFileType,
        )
        var used = false
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            { element, _ ->
                // The processor is also offered every ancestor of the hit; only the token itself matters.
                if (element.node?.firstChildNode == null && isUse(element, field, name)) used = true
                !used
            },
            scope, name, UsageSearchContext.ANY, true,
        )
        return used
    }

    private fun isUse(token: PsiElement, field: JsonnetField, name: String): Boolean = when (token.node.elementType) {
        JsonnetTypes.IDENTIFIER -> (token.parent as? JsonnetDotSuffix)?.let { couldSelect(it, field) } ?: false
        JsonnetTypes.STRING -> token.parent.let { it !is JsonnetField && it !is JsonnetImportExpr } &&
            token.text.trim('\'', '"') == name
        else -> false
    }

    private fun couldSelect(suffix: JsonnetDotSuffix, field: JsonnetField): Boolean {
        val targets = (suffix.reference as? PsiPolyVariantReference)?.multiResolve(false) ?: return true
        return targets.isEmpty() || targets.any { it.element == field }
    }
}
