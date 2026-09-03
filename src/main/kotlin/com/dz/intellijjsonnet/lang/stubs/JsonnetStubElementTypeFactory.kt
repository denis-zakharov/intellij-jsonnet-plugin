package com.dz.intellijjsonnet.lang.stubs

import com.dz.intellijjsonnet.lang.psi.JsonnetElementType
import com.intellij.psi.tree.IElementType

/**
 * Wired up via the `.bnf` file's root `elementTypeFactory` attribute, so it's
 * consulted for every composite rule's `IElementType` constant in the
 * generated `JsonnetTypes` — not just `BIND`/`FIELD`. Everything else falls
 * back to the plain [JsonnetElementType] used before stub support existed.
 */
object JsonnetStubElementTypeFactory {
    @JvmStatic
    fun factory(name: String): IElementType = when (name) {
        "BIND" -> JsonnetBindElementType(name)
        "FIELD" -> JsonnetFieldElementType(name)
        else -> JsonnetElementType(name)
    }
}
