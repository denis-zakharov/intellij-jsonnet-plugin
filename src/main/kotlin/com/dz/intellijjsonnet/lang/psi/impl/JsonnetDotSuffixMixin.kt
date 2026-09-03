package com.dz.intellijjsonnet.lang.psi.impl

import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetFieldReference
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference

abstract class JsonnetDotSuffixMixin(node: ASTNode) : ASTWrapperPsiElement(node), JsonnetDotSuffix {
    override fun getReference(): PsiReference? {
        val self = this as JsonnetDotSuffix
        if (self.nameIdentifier == null) return null
        return JsonnetFieldReference(self)
    }
}
