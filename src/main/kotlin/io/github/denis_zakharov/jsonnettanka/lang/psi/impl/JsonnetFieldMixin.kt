package io.github.denis_zakharov.jsonnettanka.lang.psi.impl

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetIcons
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetElementFactory
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver
import io.github.denis_zakharov.jsonnettanka.lang.stubs.JsonnetFieldStub
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.stubs.IStubElementType

/**
 * Only plain-identifier field names (`foo: ...`) support rename — a `'string
 * literal'` or `[computed]` field name isn't a simple leaf-token swap, and
 * matches the same "simple field" scope Phase 1's resolution already settled on.
 */
abstract class JsonnetFieldMixin : StubBasedPsiElementBase<JsonnetFieldStub>, JsonnetField, PsiNameIdentifierOwner {
    constructor(node: ASTNode) : super(node)
    constructor(stub: JsonnetFieldStub, type: IStubElementType<*, *>) : super(stub, type)

    override fun getNameIdentifier(): PsiElement? = node.findChildByType(JsonnetTypes.IDENTIFIER)?.psi

    // Falls back to JsonnetResolver.fieldNameText (not just nameIdentifier)
    // so string-literal field names resolve the same way whether or not a
    // stub is available yet.
    override fun getName(): String? = greenStub?.name ?: JsonnetResolver.fieldNameText(this)

    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(JsonnetElementFactory.createIdentifierLeaf(project, name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String? = name
        override fun getLocationString(): String? = containingFile.name
        override fun getIcon(unused: Boolean) = JsonnetIcons.FILE
    }
}
