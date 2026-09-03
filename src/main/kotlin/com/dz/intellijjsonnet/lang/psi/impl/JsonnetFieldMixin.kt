package com.dz.intellijjsonnet.lang.psi.impl

import com.dz.intellijjsonnet.lang.psi.JsonnetElementFactory
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner

/**
 * Only plain-identifier field names (`foo: ...`) support rename — a `'string
 * literal'` or `[computed]` field name isn't a simple leaf-token swap, and
 * matches the same "simple field" scope Phase 1's resolution already settled on.
 */
abstract class JsonnetFieldMixin(node: ASTNode) : ASTWrapperPsiElement(node), JsonnetField, PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(JsonnetTypes.IDENTIFIER)?.psi
    override fun getName(): String? = nameIdentifier?.text
    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(JsonnetElementFactory.createIdentifierLeaf(project, name))
        return this
    }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
}
