package io.github.denis_zakharov.jsonnettanka.lang.psi.impl

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix
import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetFieldReference
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
