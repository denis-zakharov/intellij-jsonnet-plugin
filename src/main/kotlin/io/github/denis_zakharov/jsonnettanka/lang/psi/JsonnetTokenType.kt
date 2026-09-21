package io.github.denis_zakharov.jsonnettanka.lang.psi

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage
import com.intellij.psi.tree.IElementType

class JsonnetTokenType(debugName: String) : IElementType(debugName, JsonnetLanguage) {
    override fun toString(): String = "JsonnetTokenType." + super.toString()
}
