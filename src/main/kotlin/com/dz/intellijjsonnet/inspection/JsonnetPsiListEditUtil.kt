package com.dz.intellijjsonnet.inspection

import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
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
        when {
            next?.node?.elementType == JsonnetTypes.COMMA -> {
                next.delete()
                member.delete()
            }
            prev?.node?.elementType == JsonnetTypes.COMMA -> {
                member.delete()
                prev.delete()
            }
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
