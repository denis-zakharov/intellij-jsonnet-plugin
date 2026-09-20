package com.dz.intellijjsonnet.stdlib

import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.tanka.TankaNativeFunctions
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement

/** Quick-doc for `std.<name>` and `std.native('<name>')` — see [StdLibRegistry] / [TankaNativeFunctions]. */
class StdLibDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null

        if (target.node?.elementType == JsonnetTypes.STRING && TankaNativeFunctions.isNativeNameArgument(target)) {
            val name = target.text.trim('\'', '"')
            val description = TankaNativeFunctions.describe(name) ?: return null
            return "<b>std.native('$name')</b><br/>$description" +
                "<br/><i>Injected by Tanka's Go runtime; the in-editor preview evaluates it with a JVM reimplementation.</i>"
        }

        val dotSuffix = target.parent as? JsonnetDotSuffix ?: return null
        if (!StdLibRegistry.isStdMemberAccess(dotSuffix)) return null
        val name = dotSuffix.nameIdentifier?.text ?: return null
        if (name !in StdLibRegistry.memberNames) return null
        return "<b>std.$name</b><br/>Jsonnet standard library function."
    }
}
