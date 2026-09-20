package com.dz.intellijjsonnet.engine.extension

import com.dz.intellijjsonnet.shaded.scala.collection.immutable.Map as ScalaMap
import com.dz.intellijjsonnet.shaded.sjsonnet.Eval
import com.dz.intellijjsonnet.shaded.sjsonnet.EvalScope
import com.dz.intellijjsonnet.shaded.sjsonnet.Position
import com.dz.intellijjsonnet.shaded.sjsonnet.`TailstrictModeDisabled$`
import com.dz.intellijjsonnet.shaded.sjsonnet.Val

/**
 * `std` functions go-jsonnet has and `sjsonnet` 0.7.4 lacks, or has under different *parameter names*
 * (which breaks named-argument calls such as `std.hypot(x=3, y=4)`). Found by running go-jsonnet's
 * `testdata/` and a per-parameter probe against both; see `docs/sjsonnet-gaps.md`.
 */
internal object StdExtras {

    /**
     * go-jsonnet's parameter names, for the built-ins where sjsonnet's differ. Positional order is the
     * same in both, so each entry is a pure rename. Every entry was confirmed against the real
     * `jsonnet` binary (`std.f(name=...)` accepted by go, rejected by sjsonnet).
     */
    // Not `native` (go: `x`, sjsonnet: `name`): StdLibModule appends it *after* merging the extras, so it can't be replaced.
    val goParameterNames: Map<String, List<String>> = mapOf(
        "reverse" to listOf("arr"),
        "removeAt" to listOf("arr", "i"),
        "equals" to listOf("x", "y"),
        "objectFieldsEx" to listOf("obj", "hidden"),
        "objectHasEx" to listOf("obj", "fname", "hidden"),
        "hypot" to listOf("x", "y"),
        "modulo" to listOf("x", "y"),
        "sha1" to listOf("s"),
        "sha256" to listOf("s"),
        "sha512" to listOf("s"),
        "sha3" to listOf("s"),
        "isNull" to listOf("x"),
        "escapeStringXML" to listOf("str_"),
        "manifestJson" to listOf("value"),
    )

    fun build(builtins: ScalaMap<String, Val.Func>): Map<String, Val.Func> {
        val extras = LinkedHashMap<String, Val.Func>()
        extras["id"] = object : Val.Builtin1("id", "x", null) {
            override fun evalRhs(arg1: Eval, ev: EvalScope, pos: Position): Val = arg1.value()
        }
        for ((name, params) in goParameterNames) {
            val original = builtins.get(name)
            if (original.isEmpty) continue // a later sjsonnet may drop or rename it; nothing to alias then
            extras[name] = rename(name, params, original.get()) ?: continue
        }
        return extras
    }

    /** Re-declares [target] under [params]; `null` if the arity no longer matches (sjsonnet changed under us). */
    private fun rename(name: String, params: List<String>, target: Val.Func): Val.Func? {
        if (target.params().names().size != params.size) return null
        val tail = `TailstrictModeDisabled$`.`MODULE$`
        return when (params.size) {
            1 -> object : Val.Builtin1(name, params[0], null) {
                override fun evalRhs(arg1: Eval, ev: EvalScope, pos: Position): Val =
                    target.apply1(arg1, pos, ev, tail)
            }
            2 -> object : Val.Builtin2(name, params[0], params[1], null) {
                override fun evalRhs(arg1: Eval, arg2: Eval, ev: EvalScope, pos: Position): Val =
                    target.apply2(arg1, arg2, pos, ev, tail)
            }
            3 -> object : Val.Builtin3(name, params[0], params[1], params[2], null) {
                override fun evalRhs(arg1: Eval, arg2: Eval, arg3: Eval, ev: EvalScope, pos: Position): Val =
                    target.apply3(arg1, arg2, arg3, pos, ev, tail)
            }
            else -> null
        }
    }
}
