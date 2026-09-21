package io.github.denis_zakharov.jsonnettanka.editor.structure

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetFile
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetFunctionExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetLocalExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetObjectLiteral
import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * One node in the Structure View: a `local` bind or an object field, shown
 * with its own nested locals/fields/functions recursively. Arrays and
 * comprehensions aren't expanded — they don't carry names worth navigating to.
 */
class JsonnetStructureElement(private val element: PsiElement) : StructureViewTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? NavigatablePsiElement)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? NavigatablePsiElement)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = presentableText(element)
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean) = null
    }

    override fun getChildren(): Array<TreeElement> {
        val childElements = when (element) {
            is JsonnetFile -> childrenOfExpr(PsiTreeUtil.findChildOfType(element, JsonnetExpr::class.java))
            is JsonnetLocalExpr -> element.bindList + childrenOfExpr(element.expr)
            is JsonnetBind -> childrenOfExpr(element.expr)
            is JsonnetObjectLiteral -> {
                val members = element.objectMemberList
                (members?.objectLocalList?.mapNotNull { it.bind } ?: emptyList()) +
                    (members?.fieldList ?: emptyList())
            }
            is JsonnetField -> childrenOfExpr(element.expr)
            is JsonnetFunctionExpr -> childrenOfExpr(element.expr)
            else -> emptyList()
        }
        return childElements.map { JsonnetStructureElement(it) }.toTypedArray()
    }

    /** Unwraps a leading `local` chain and descends into function bodies, surfacing the "interesting" node. */
    private fun childrenOfExpr(expr: JsonnetExpr?): List<PsiElement> {
        if (expr == null) return emptyList()
        PsiTreeUtil.getChildOfType(expr, JsonnetLocalExpr::class.java)?.let { return listOf(it) }
        PsiTreeUtil.getChildOfType(expr, JsonnetObjectLiteral::class.java)?.let { return listOf(it) }
        PsiTreeUtil.getChildOfType(expr, JsonnetFunctionExpr::class.java)?.let { return listOf(it) }
        return emptyList()
    }
}

private fun presentableText(element: PsiElement): String = when (element) {
    is JsonnetFile -> element.name
    is JsonnetBind -> element.nameIdentifier?.text.orEmpty() + if (element.paramList != null) "(...)" else ""
    is JsonnetField -> JsonnetResolver.fieldNameText(element) ?: "[...]"
    is JsonnetObjectLiteral -> "{...}"
    is JsonnetFunctionExpr -> "function(...)"
    else -> element.text.take(24)
}
