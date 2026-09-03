package com.dz.intellijjsonnet.lang.psi.impl

import com.dz.intellijjsonnet.lang.JsonnetIcons
import com.dz.intellijjsonnet.lang.psi.JsonnetElementFactory
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver
import com.dz.intellijjsonnet.lang.stubs.JsonnetFieldStub
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
        override fun getLocationString(): String? = containingFile?.name
        override fun getIcon(unused: Boolean) = JsonnetIcons.FILE
    }
}
