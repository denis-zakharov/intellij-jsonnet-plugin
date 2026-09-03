package com.dz.intellijjsonnet.lang.stubs

import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubBase
import com.intellij.psi.stubs.StubElement

/**
 * @param topLevel whether [JsonnetStubIndexUtil.isTopLevelBindDecl] held at stub-creation
 *   time (already accounts for `vendor/` exclusion) — the only binds actually
 *   added to [JsonnetBindIndex].
 * @param hasParams whether the bind has a `paramList` (`local Foo(x) = ...;`)
 *   — the same rule shape covers both plain locals and "function defs" from
 *   the plan doc's Phase 4 checklist, distinguished by this flag.
 */
class JsonnetBindStub(
    parent: StubElement<*>?,
    elementType: IStubElementType<*, *>,
    val name: String?,
    val topLevel: Boolean,
    val hasParams: Boolean,
) : StubBase<JsonnetBind>(parent, elementType)
