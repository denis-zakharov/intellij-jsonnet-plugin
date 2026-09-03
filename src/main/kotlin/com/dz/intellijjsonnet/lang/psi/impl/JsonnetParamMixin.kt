package com.dz.intellijjsonnet.lang.psi.impl

import com.dz.intellijjsonnet.lang.psi.JsonnetElementFactory
import com.dz.intellijjsonnet.lang.psi.JsonnetParam
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner

abstract class JsonnetParamMixin(node: ASTNode) : ASTWrapperPsiElement(node), JsonnetParam, PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = node.findChildByType(JsonnetTypes.IDENTIFIER)?.psi
    override fun getName(): String? = nameIdentifier?.text
    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(JsonnetElementFactory.createIdentifierLeaf(project, name))
        return this
    }
    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()
}
