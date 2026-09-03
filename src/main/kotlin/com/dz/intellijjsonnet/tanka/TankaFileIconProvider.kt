package com.dz.intellijjsonnet.tanka

import com.dz.intellijjsonnet.lang.JsonnetIcons
import com.intellij.ide.IconProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

/** Distinguishes `spec.json`/`jsonnetfile.json` from plain JSON in the project view. */
class TankaFileIconProvider : IconProvider() {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val file = element as? PsiFile ?: return null
        return when (file.name) {
            "spec.json", "jsonnetfile.json" -> JsonnetIcons.FILE
            else -> null
        }
    }
}
