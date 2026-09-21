package io.github.denis_zakharov.jsonnettanka.lang.psi.impl

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetIcons
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetElementFactory
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import io.github.denis_zakharov.jsonnettanka.lang.stubs.JsonnetBindStub
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.stubs.IStubElementType

abstract class JsonnetBindMixin : StubBasedPsiElementBase<JsonnetBindStub>, JsonnetBind, PsiNameIdentifierOwner {
    constructor(node: ASTNode) : super(node)
    constructor(stub: JsonnetBindStub, type: IStubElementType<*, *>) : super(stub, type)

    override fun getNameIdentifier(): PsiElement? = node.findChildByType(JsonnetTypes.IDENTIFIER)?.psi

    // Prefer the stub's name when one is available (e.g. from the index,
    // without switching to AST) — falls back to the live identifier otherwise.
    override fun getName(): String? = greenStub?.name ?: nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(JsonnetElementFactory.createIdentifierLeaf(project, name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String? = name
        override fun getLocationString(): String? = containingFile?.name
        override fun getIcon(unused: Boolean) = JsonnetIcons.FILE
    }
}
