package com.dz.intellijjsonnet.lang.psi.impl

import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetLocalReference
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference

abstract class JsonnetNameRefMixin(node: ASTNode) : ASTWrapperPsiElement(node), JsonnetNameRef {
    override fun getReference(): PsiReference? {
        val self = this as JsonnetNameRef
        if (self.nameIdentifier == null) return null
        return JsonnetLocalReference(self)
    }
}
