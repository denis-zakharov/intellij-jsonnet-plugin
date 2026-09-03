package com.dz.intellijjsonnet.editor

import com.dz.intellijjsonnet.lang.psi.JsonnetObjectLiteral
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

class JsonnetFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        for (obj in PsiTreeUtil.findChildrenOfType(root, JsonnetObjectLiteral::class.java)) {
            if (obj.textRange.length > 1) {
                descriptors += FoldingDescriptor(obj.node, obj.textRange)
            }
        }
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "{...}"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
