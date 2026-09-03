package com.dz.intellijjsonnet.stdlib

import com.dz.intellijjsonnet.engine.InMemoryPath
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.shaded.scala.collection.immutable.`Map$`
import com.dz.intellijjsonnet.shaded.scala.util.Right
import com.dz.intellijjsonnet.shaded.sjsonnet.DefaultParseCache
import com.dz.intellijjsonnet.shaded.sjsonnet.Importer
import com.dz.intellijjsonnet.shaded.sjsonnet.Interpreter
import com.dz.intellijjsonnet.shaded.sjsonnet.Val
import com.intellij.psi.TokenType

/**
 * The list of `std.*` member names, read directly off a real evaluation of the
 * bare expression `std` (not the raw default constructor argument — that
 * instance's `visibleKeyNames()` comes back empty; it looks like `std`'s key
 * list is only fully populated once bound through an actual `Interpreter`
 * evaluation) rather than hand-maintained — per the plan (§6), this is what
 * keeps completion/hover accurate to whatever `sjsonnet` version is actually
 * pinned, with no separate list to fall out of sync on a dependency bump.
 */
object StdLibRegistry {

    val memberNames: List<String> by lazy { computeMemberNames() }

    private fun computeMemberNames(): List<String> {
        val path = InMemoryPath("std-probe.jsonnet", "std")
        val emptyMap = `Map$`.`MODULE$`.empty<String, String>()
        val interpreter = Interpreter(
            emptyMap,
            emptyMap,
            path,
            Importer.empty(),
            DefaultParseCache(),
            Interpreter.`$lessinit$greater$default$6`(),
            Interpreter.`$lessinit$greater$default$7`(),
            Interpreter.`$lessinit$greater$default$8`(),
            Interpreter.`$lessinit$greater$default$9`(),
            Interpreter.`$lessinit$greater$default$10`(),
        )
        val result = interpreter.evaluate("std", path)
        val value = (result as? Right<*, *>)?.value() as? Val.Obj ?: return emptyList()
        // std's own functions are internally hidden fields (`::`, matching real
        // Jsonnet's std.jsonnet convention) — visibleKeyNames() excludes them
        // entirely, so allKeyNames() is what we actually want here.
        return value.allKeyNames().toList().filterNot { it.startsWith("__") }.sorted()
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
