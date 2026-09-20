package com.dz.intellijjsonnet.lang.psi.impl

import com.dz.intellijjsonnet.lang.psi.JsonnetImportExpr
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetImportReferences
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference

/** Hosts the file references for the path string — see [JsonnetImportReferences] for why it's not the string token. */
abstract class JsonnetImportExprMixin(node: ASTNode) : ASTWrapperPsiElement(node), JsonnetImportExpr {
    override fun getReferences(): Array<PsiReference> = JsonnetImportReferences.create(this as JsonnetImportExpr)
}
