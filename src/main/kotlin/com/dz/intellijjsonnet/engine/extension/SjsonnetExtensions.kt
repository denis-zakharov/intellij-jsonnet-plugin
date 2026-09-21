package com.dz.intellijjsonnet.engine.extension

import com.dz.intellijjsonnet.shaded.scala.collection.immutable.`Map$`
import com.dz.intellijjsonnet.shaded.scala.collection.immutable.Map as ScalaMap
import com.dz.intellijjsonnet.shaded.sjsonnet.Val
import com.dz.intellijjsonnet.shaded.sjsonnet.stdlib.StdLibModule

/**
 * The plugin's in-project extension to `sjsonnet`: the `std` object every interpreter the plugin
 * builds is constructed with, instead of sjsonnet's stock one.
 *
 * `sjsonnet` deliberately has no "register a native function" call on `Interpreter`/`Settings`, but
 * its `StdLibModule(nativeFunctions, additionalStdFunctions)` constructor is exactly that hook —
 * `Interpreter`'s 9th argument is the `std` object, and `StdLibModule.module()` produces one. Both
 * maps are merged *over* the built-ins, so [StdExtras] can add missing functions and re-declare
 * existing ones under go-jsonnet's parameter names, and [TankaNatives] can back `std.native(...)`.
 *
 * What is (and deliberately is not) covered here, and why, is recorded in `docs/sjsonnet-gaps.md`.
 * Use this everywhere an `Interpreter` is constructed so evaluation, static checking and the `std`
 * completion list all see the same functions.
 */
object SjsonnetExtensions {

    private val module: StdLibModule by lazy {
        val none = scalaMapOf<Val.Func>(emptyMap())
        // Stock built-ins: what the natives delegate to (`parseJson`)...
        val stock = StdLibModule(none, none).functions()
        val natives = TankaNatives.build(stock)
        // ...and what the parameter-renaming wrappers delegate to (incl. `std.native`, bound to our natives).
        val builtins = StdLibModule(natives, none).functions()
        StdLibModule(natives, scalaMapOf(StdExtras.build(builtins)))
    }

    /** The extended `std`; built once, it is immutable and safe to share between interpreters. */
    val std: Val.Obj by lazy { module.module() }

    /** Every function of [std] by name, for reading arities and parameter names without evaluating anything. */
    val functions: Map<String, Val.Func> by lazy {
        val result = LinkedHashMap<String, Val.Func>()
        val iterator = module.functions().iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            result[entry._1()] = entry._2()
        }
        result
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <V> scalaMapOf(entries: Map<String, V>): ScalaMap<String, V> {
        var result: ScalaMap<String, V> = `Map$`.`MODULE$`.empty()
        for ((key, value) in entries) result = result.updated(key, value) as ScalaMap<String, V>
        return result
    }
}
