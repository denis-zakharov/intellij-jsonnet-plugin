package com.dz.intellijjsonnet.lang.psi

import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.intellij.psi.tree.IElementType

class JsonnetTokenType(debugName: String) : IElementType(debugName, JsonnetLanguage) {
    override fun toString(): String = "JsonnetTokenType." + super.toString()
}
