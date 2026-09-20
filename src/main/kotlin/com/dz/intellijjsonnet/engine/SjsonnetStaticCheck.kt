package com.dz.intellijjsonnet.engine

import com.dz.intellijjsonnet.engine.extension.SjsonnetExtensions
import com.dz.intellijjsonnet.shaded.scala.util.Left
import com.dz.intellijjsonnet.shaded.scala.util.Right
import com.dz.intellijjsonnet.shaded.sjsonnet.DefaultParseCache
import com.dz.intellijjsonnet.shaded.sjsonnet.Error as SjsonnetError
import com.dz.intellijjsonnet.shaded.sjsonnet.Expr
import com.dz.intellijjsonnet.shaded.sjsonnet.Importer
import com.dz.intellijjsonnet.shaded.sjsonnet.Interpreter
import com.dz.intellijjsonnet.shaded.sjsonnet.ParseError
import com.dz.intellijjsonnet.shaded.sjsonnet.StaticResolvedFile

/**
 * TODO.md item 3: cross-checks [com.dz.intellijjsonnet.editor.JsonnetUnresolvedReferenceAnnotator]'s
 * own hand-rolled [com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver] lexical-scope walk
 * against sjsonnet's actual scope resolution — the same static analysis real Jsonnet runs before
 * every evaluation — so a divergence (our resolver missing something real Jsonnet would reject)
 * gets caught, closing the "hand-rolled resolver can silently diverge from real Jsonnet scoping
 * rules" trust gap.
 *
 * Confirmed wireable via `javap` on `sjsonnet_3-0.7.4.jar` (see AGENTS.md): `Interpreter` already
 * exposes `resolver()`, `evaluator()`, `createOptimizer(...)`, `internedStrings()`, and
 * `internedStaticFieldSets()` as public accessors, so no separate manual `EvalScope`/`std`
 * construction is needed beyond building an `Interpreter` the same way [JsonnetEngine] already
 * does. Unresolved-variable detection is purely lexical (import boundaries don't leak names into
 * a file's scope in Jsonnet), so `Importer.empty()` is correct here even for a file that has real
 * imports — we're not evaluating them, just checking name resolution within this one file's text.
 *
 * **Where the check actually happens, corrected from the original plan**: Id-to-slot-index
 * resolution (needed for sjsonnet's slot-based variable environment) turns out to be done eagerly
 * by [sjsonnet.CachedResolver.parse] itself — *not* by a later, separate `StaticOptimizer.optimize()`
 * call as `StaticOptimizer`'s own "Unknown variable" string constant first suggested. An unresolved
 * name surfaces as `parse()` returning `Left(a sjsonnet.Error)`, not `Right`. (`optimize()` is still
 * called and still wrapped in a matching try/catch, since some optimizer-only rewrites — e.g.
 * self-tail-call rebinding — could plausibly hit the same failure mode; belt and suspenders, cheap
 * to keep both paths covered.) `ParseError` (a real syntax error — the PSI parser/annotator already
 * reports those) is filtered out explicitly so this only ever reports genuine unresolved-name
 * divergences, never syntax errors.
 */
object SjsonnetStaticCheck {

    data class UnresolvedVariable(val offset: Int, val message: String)

    /** `null` means: no unresolved variable found (or a real syntax error — the PSI-side annotator/parser already reports those, no need to double up here). */
    fun firstUnresolvedVariable(fileName: String, source: String): UnresolvedVariable? {
        val path = InMemoryPath(fileName, source)
        val parseCache = DefaultParseCache()
        val settings = Interpreter.`$lessinit$greater$default$6`()
        val storePos = Interpreter.`$lessinit$greater$default$7`()
        val logger = Interpreter.`$lessinit$greater$default$8`()
        val std = SjsonnetExtensions.std
        val variableResolver = Interpreter.`$lessinit$greater$default$10`()

        val interpreter = Interpreter(
            `Map$empty`(),
            `Map$empty`(),
            path,
            Importer.empty(),
            parseCache,
            settings,
            storePos,
            logger,
            std,
            variableResolver,
        )

        val evalScope = interpreter.evaluator()
        val resolvedFile = StaticResolvedFile(source)

        return try {
            val parsed = interpreter.resolver().parse(path, resolvedFile, evalScope)
            val expr: Expr = when (parsed) {
                is Right<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val tuple = parsed.value() as com.dz.intellijjsonnet.shaded.scala.Tuple2<Expr, *>
                    tuple._1()
                }
                is Left<*, *> -> {
                    val error = parsed.value()
                    if (error is ParseError) return null
                    return toUnresolvedVariable(error as SjsonnetError)
                }
                else -> return null
            }

            val optimizer = interpreter.createOptimizer(evalScope, std, interpreter.internedStrings(), interpreter.internedStaticFieldSets())
            optimizer.optimize(expr)
            null
        } catch (e: SjsonnetError) {
            toUnresolvedVariable(e)
        }
    }

    private fun toUnresolvedVariable(e: SjsonnetError): UnresolvedVariable {
        val frames = e.stack()
        val offset = if (!frames.isEmpty()) frames.head().pos().offset() else 0
        return UnresolvedVariable(offset, e.message ?: "Unresolved reference")
    }

    private fun `Map$empty`(): com.dz.intellijjsonnet.shaded.scala.collection.immutable.Map<String, String> =
        com.dz.intellijjsonnet.shaded.scala.collection.immutable.`Map$`.`MODULE$`.empty()
}
