package io.github.denis_zakharov.jsonnettanka.lang.stubs

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubIndexKey

/** Project-wide name -> top-level `local` bind lookup, powers [JsonnetGotoSymbolContributor]. */
class JsonnetBindIndex : StringStubIndexExtension<JsonnetBind>() {
    override fun getKey(): StubIndexKey<String, JsonnetBind> = KEY
    override fun getVersion(): Int = JsonnetFileElementType.VERSION

    companion object {
        val KEY: StubIndexKey<String, JsonnetBind> = StubIndexKey.createIndexKey("jsonnet.bind.name")
    }
}
