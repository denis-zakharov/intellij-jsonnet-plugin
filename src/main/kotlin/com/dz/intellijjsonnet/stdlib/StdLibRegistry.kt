package com.dz.intellijjsonnet.stdlib

import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.shaded.sjsonnet.Interpreter
import com.intellij.psi.TokenType

/**
 * The list of `std.*` member names, read directly off the shaded interpreter's
 * own default standard-library object at runtime (`Val$Obj.visibleKeyNames()`)
 * rather than hand-maintained — per the plan (§6), this is what keeps
 * completion/hover accurate to whatever `sjsonnet` version is actually pinned,
 * with no separate list to fall out of sync on a dependency bump.
 */
object StdLibRegistry {

    val memberNames: List<String> by lazy {
        val std = Interpreter.`$lessinit$greater$default$9`()
        std.visibleKeyNames().toList().sorted()
    }

    /** True when [dotSuffix] is directly `std.<name>` — the same narrow "simple receiver" scope as field resolution. */
    fun isStdMemberAccess(dotSuffix: JsonnetDotSuffix): Boolean {
        val exprParent = dotSuffix.parent as? JsonnetExpr ?: return false
        var prev = dotSuffix.prevSibling
        while (prev != null && prev.node.elementType == TokenType.WHITE_SPACE) {
            prev = prev.prevSibling
        }
        val receiver = prev as? JsonnetNameRef ?: return false
        return receiver.parent == exprParent && receiver.text == "std"
    }
}
