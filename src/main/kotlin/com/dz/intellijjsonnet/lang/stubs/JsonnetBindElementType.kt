package com.dz.intellijjsonnet.lang.stubs

import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.impl.JsonnetBindImpl
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream

class JsonnetBindElementType(debugName: String) :
    IStubElementType<JsonnetBindStub, JsonnetBind>(debugName, JsonnetLanguage) {

    override fun getExternalId(): String = "jsonnet.BIND"

    override fun createPsi(stub: JsonnetBindStub): JsonnetBind = JsonnetBindImpl(stub, this)

    override fun createStub(psi: JsonnetBind, parentStub: StubElement<*>?): JsonnetBindStub {
        val name = psi.nameIdentifier?.text
        return JsonnetBindStub(
            parentStub,
            this,
            name,
            JsonnetStubIndexUtil.isTopLevelBindDecl(psi),
            psi.paramList != null,
        )
    }

    override fun serialize(stub: JsonnetBindStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeBoolean(stub.topLevel)
        dataStream.writeBoolean(stub.hasParams)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): JsonnetBindStub {
        val name = dataStream.readNameString()
        val topLevel = dataStream.readBoolean()
        val hasParams = dataStream.readBoolean()
        return JsonnetBindStub(parentStub, this, name, topLevel, hasParams)
    }

    override fun indexStub(stub: JsonnetBindStub, sink: IndexSink) {
        val name = stub.name
        if (stub.topLevel && name != null) {
            sink.occurrence(JsonnetBindIndex.KEY, name)
        }
    }
}
