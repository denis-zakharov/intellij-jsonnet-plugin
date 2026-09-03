package com.dz.intellijjsonnet.stdlib

import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement

/** Quick-doc for `std.<name>` — same accuracy guarantee as completion: sourced from [StdLibRegistry]. */
class StdLibDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val dotSuffix = (originalElement?.parent ?: element?.parent) as? JsonnetDotSuffix ?: return null
        if (!StdLibRegistry.isStdMemberAccess(dotSuffix)) return null
        val name = dotSuffix.nameIdentifier?.text ?: return null
        if (name !in StdLibRegistry.memberNames) return null
        return "<b>std.$name</b><br/>Jsonnet standard library function."
    }
}
