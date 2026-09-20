package com.dz.intellijjsonnet.lang.psi

import com.dz.intellijjsonnet.lang.JsonnetFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil

/** Builds throwaway PSI fragments to lift real leaf nodes out of (the standard rename-support trick). */
object JsonnetElementFactory {

    fun createIdentifierLeaf(project: Project, name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "rename.jsonnet",
            JsonnetFileType,
            "local $name = null; null",
        ) as JsonnetFile
        val bind = PsiTreeUtil.findChildOfType(file, JsonnetBind::class.java)!!
        return bind.node.findChildByType(JsonnetTypes.IDENTIFIER)!!.psi
    }

    /** A standalone `import '...'` expression from [text], or null if it doesn't parse as one. */
    fun createImportExpr(project: Project, text: String): JsonnetImportExpr? {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "import.jsonnet",
            JsonnetFileType,
            "local x = $text; x",
        ) as JsonnetFile
        return PsiTreeUtil.findChildOfType(file, JsonnetImportExpr::class.java)
    }
}
