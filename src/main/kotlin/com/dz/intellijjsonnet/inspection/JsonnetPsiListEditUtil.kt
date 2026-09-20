package com.dz.intellijjsonnet.inspection

import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.intellij.extapi.psi.ASTDelegatePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType

/**
 * Deletes one member of a comma-separated list (`objectMemberList`, a
 * `localExpr`'s bind list, ...) along with one adjacent comma, so the
 * remaining list stays syntactically valid. Shared by
 * [JsonnetUnusedDeclarationInspection]'s quick fixes.
 */
object JsonnetPsiListEditUtil {

    fun deleteListMember(member: PsiElement) {
        val next = nextNonWhitespace(member)
        val prev = prevNonWhitespace(member)
        // deleteChildRange (not two separate .delete() calls) so the
        // whitespace between the member and its comma — a separate sibling
        // node — goes with it in one atomic AST edit, instead of being left
        // behind as e.g. a stray space before `;` (or, worse, invalidating
        // the second element's stale PsiElement reference after the first
        // .delete() triggers its own tree rebalancing).
        val parent = member.parent as? ASTDelegatePsiElement
        when {
            next?.node?.elementType == JsonnetTypes.COMMA && parent != null -> parent.deleteChildRange(member, next)
            prev?.node?.elementType == JsonnetTypes.COMMA && parent != null -> parent.deleteChildRange(prev, member)
            else -> member.delete()
        }
    }

    private fun nextNonWhitespace(element: PsiElement): PsiElement? {
        var sibling = element.nextSibling
        while (sibling != null && sibling.node.elementType == TokenType.WHITE_SPACE) sibling = sibling.nextSibling
        return sibling
    }

    private fun prevNonWhitespace(element: PsiElement): PsiElement? {
        var sibling = element.prevSibling
        while (sibling != null && sibling.node.elementType == TokenType.WHITE_SPACE) sibling = sibling.prevSibling
        return sibling
    }
}
