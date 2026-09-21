package io.github.denis_zakharov.jsonnettanka.lang.stubs

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndexKey

/** Project-wide name -> top-level object-field lookup, powers [JsonnetGotoSymbolContributor]. */
class JsonnetFieldIndex : StringStubIndexExtension<JsonnetField>() {
    override fun getKey(): StubIndexKey<String, JsonnetField> = KEY
    override fun getVersion(): Int = JsonnetFileElementType.VERSION

    companion object {
        val KEY: StubIndexKey<String, JsonnetField> = StubIndexKey.createIndexKey("jsonnet.field.name")
    }
}
