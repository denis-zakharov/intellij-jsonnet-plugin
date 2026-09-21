package io.github.denis_zakharov.jsonnettanka.lang.psi.impl

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef
import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetLocalReference
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
