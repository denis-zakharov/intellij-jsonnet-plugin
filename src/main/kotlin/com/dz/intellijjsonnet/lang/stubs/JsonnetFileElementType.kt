package com.dz.intellijjsonnet.lang.stubs

import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.psi.tree.IStubFileElementType

class JsonnetFileElementType : IStubFileElementType<PsiFileStub<*>>(JsonnetLanguage) {
    override fun getStubVersion(): Int = VERSION
    override fun getExternalId(): String = "jsonnet.FILE"

    companion object {
        // Bump whenever stub shape changes (new/renamed stub fields, or which
        // declarations get a stub at all) so the platform rebuilds indices
        // instead of reading stale ones.
        const val VERSION = 1
    }
}
