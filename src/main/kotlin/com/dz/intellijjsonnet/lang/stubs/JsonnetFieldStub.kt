package com.dz.intellijjsonnet.lang.stubs

import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubBase
import com.intellij.psi.stubs.StubElement

/** See [JsonnetBindStub] — same shape, for object-field declarations. */
class JsonnetFieldStub(
    parent: StubElement<*>?,
    elementType: IStubElementType<*, *>,
    val name: String?,
    val topLevel: Boolean,
    val hasParams: Boolean,
) : StubBase<JsonnetField>(parent, elementType)
