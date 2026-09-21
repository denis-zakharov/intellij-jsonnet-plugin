package io.github.denis_zakharov.jsonnettanka.lang.stubs

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.impl.JsonnetFieldImpl
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream

class JsonnetFieldElementType(debugName: String) :
    IStubElementType<JsonnetFieldStub, JsonnetField>(debugName, JsonnetLanguage) {

    override fun getExternalId(): String = "jsonnet.FIELD"

    override fun createPsi(stub: JsonnetFieldStub): JsonnetField = JsonnetFieldImpl(stub, this)

    override fun createStub(psi: JsonnetField, parentStub: StubElement<*>?): JsonnetFieldStub {
        // Only plain-identifier/string-literal names are indexable — a
        // `[computed]` field name has no static name to index by.
        val name = JsonnetResolver.fieldNameText(psi)
        return JsonnetFieldStub(
            parentStub,
            this,
            name,
            JsonnetStubIndexUtil.isTopLevelFieldDecl(psi),
            psi.paramList != null,
        )
    }

    override fun serialize(stub: JsonnetFieldStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
        dataStream.writeBoolean(stub.topLevel)
        dataStream.writeBoolean(stub.hasParams)
    }

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?): JsonnetFieldStub {
        val name = dataStream.readNameString()
        val topLevel = dataStream.readBoolean()
        val hasParams = dataStream.readBoolean()
        return JsonnetFieldStub(parentStub, this, name, topLevel, hasParams)
    }

    override fun indexStub(stub: JsonnetFieldStub, sink: IndexSink) {
        val name = stub.name
        if (stub.topLevel && name != null) {
            sink.occurrence(JsonnetFieldIndex.KEY, name)
        }
    }
}
