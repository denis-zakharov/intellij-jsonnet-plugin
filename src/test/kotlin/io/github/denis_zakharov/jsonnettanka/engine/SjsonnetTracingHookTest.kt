package io.github.denis_zakharov.jsonnettanka.engine

import io.github.denis_zakharov.jsonnettanka.engine.extension.SjsonnetExtensions
import io.github.denis_zakharov.jsonnettanka.shaded.scala.Function1
import io.github.denis_zakharov.jsonnettanka.shaded.scala.Function2
import io.github.denis_zakharov.jsonnettanka.shaded.scala.Option
import io.github.denis_zakharov.jsonnettanka.shaded.scala.`Option$`
import io.github.denis_zakharov.jsonnettanka.shaded.scala.collection.immutable.`Map$`
import io.github.denis_zakharov.jsonnettanka.shaded.scala.collection.mutable.HashMap as ScalaHashMap
import io.github.denis_zakharov.jsonnettanka.shaded.scala.runtime.BoxedUnit
import io.github.denis_zakharov.jsonnettanka.shaded.scala.util.Right
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.CachedResolver
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.DefaultParseCache
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Eval
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Evaluator
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Expr
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.FormatCache
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Importer
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Interpreter
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Path
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.ResolvedFile
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Settings
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.StaticResolvedFile
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Val
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TODO.md item 13: the "sjsonnet has no evaluation-tracing hook" premise (behind not building a debugger) was
 * wrong. `Interpreter.createEvaluator` and `Evaluator.visitExpr` are public and non-final, so a subclass sees
 * every expression the evaluator dispatches, with its file and offset, on the thread that evaluates it —
 * enough to block there (a breakpoint) and read the scope. No reflection needed.
 *
 * Nothing in the plugin uses this yet; these tests exist so a sjsonnet bump that closes the door (a `final`,
 * a changed `createEvaluator` signature) fails here instead of silently. They deliberately don't assert
 * *which* expressions bypass the hook: sjsonnet's eager/pure-arithmetic fast paths and constant folding skip
 * some trivial ones (`x * 2` with plain-number operands, `std.length("abc")`), and that may change.
 */
class SjsonnetTracingHookTest {

    private class MemPath(val name: String, val text: String) : Path {
        override fun relativeToString(base: Path) = name
        override fun parent(): Path = MemPath("", "")
        override fun segmentCount() = 1
        override fun last() = name
        override fun `$div`(segment: String): Path = MemPath(segment, "")
        override fun renderOffsetStr(offset: Int, cache: ScalaHashMap<Path, IntArray>) = "$name@$offset"
        override fun toString() = name
    }

    private class MemImporter(files: List<MemPath>) : Importer() {
        private val byName = files.associateBy { it.name }
        override fun resolve(docBase: Path, importName: String): Option<Path> =
            `Option$`.`MODULE$`.apply(byName[importName] as Path?)
        override fun read(path: Path, binaryData: Boolean): Option<ResolvedFile> =
            `Option$`.`MODULE$`.apply(StaticResolvedFile((path as MemPath).text) as ResolvedFile)
    }

    /** An interpreter whose evaluator reports every `visitExpr` — the whole hook. */
    private class TracingInterpreter(
        root: Path,
        importer: Importer,
        private val onExpr: (Expr, Array<Eval>) -> Unit,
    ) : Interpreter(
        `Map$`.`MODULE$`.empty<String, String>(),
        `Map$`.`MODULE$`.empty<String, String>(),
        root,
        importer,
        DefaultParseCache(),
        Interpreter.`$lessinit$greater$default$6`(),
        Interpreter.`$lessinit$greater$default$7`(),
        Interpreter.`$lessinit$greater$default$8`(),
        SjsonnetExtensions.std,
        Interpreter.`$lessinit$greater$default$10`(),
    ) {
        override fun createEvaluator(
            resolver: CachedResolver,
            extVars: Function1<String, Option<Expr>>,
            wd: Path,
            settings: Settings,
        ): Evaluator {
            // The default logger is null in sjsonnet; Evaluator accepts that.
            val logger: Function2<Any, String, BoxedUnit>? = Interpreter.`$lessinit$greater$default$8`()
            return object : Evaluator(resolver, extVars, wd, settings, logger, debugStats(), FormatCache.SharedDefault()) {
                override fun visitExpr(e: Expr, scope: Array<Eval>): Val {
                    onExpr(e, scope)
                    return super.visitExpr(e, scope)
                }
            }
        }
    }

    private val lib = MemPath("lib.libsonnet", "{ double(x):: local twice = x * 2; twice + 0 }")
    private val main = MemPath("main.jsonnet", "local l = import 'lib.libsonnet'; { a: l.double(21) }")

    private fun fileOf(e: Expr): String = (e.pos().currentFile() as MemPath).name

    @Test
    fun `every dispatched expression is reported with its file and offset, across imports`() {
        val seen = mutableListOf<Triple<String, Int, String>>()
        val interpreter = TracingInterpreter(main, MemImporter(listOf(lib))) { e, _ ->
            seen += Triple(fileOf(e), e.pos().offset(), e.javaClass.simpleName)
        }

        val result = interpreter.interpret(main.text, main)

        assertEquals("""{"a":42}""", (result as Right<*, *>).value().toString())
        // the call in main.jsonnet ("l.double(21)" starts at offset 47) ...
        assertTrue(Triple("main.jsonnet", 47, "Apply1") in seen, seen.toString())
        // ... and the body of the imported function (the `x * 2` at offset 41)
        assertTrue(Triple("lib.libsonnet", 41, "BinaryOp") in seen, seen.toString())
    }

    @Test
    fun `evaluation can be blocked inside the hook and its scope read from another thread`() {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var pausedScope: List<Val?> = emptyList()
        val interpreter = TracingInterpreter(main, MemImporter(listOf(lib))) { e, scope ->
            if (fileOf(e) == "lib.libsonnet" && e is Expr.BinaryOp && pausedScope.isEmpty()) {
                pausedScope = scope.map { it?.value() }
                paused.countDown()
                assertTrue(resume.await(10, TimeUnit.SECONDS))
            }
        }
        var result: Any? = null
        val worker = Thread({ result = interpreter.interpret(main.text, main) }, "eval-worker")
        worker.start()

        assertTrue(paused.await(10, TimeUnit.SECONDS), "never reached the breakpoint")
        assertTrue(worker.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING), "the evaluating thread should be parked inside the hook, was ${worker.state}")
        // `l.double(21)`: the argument is visible in the scope as a value (slots have no names — see TODO.md 13)
        assertTrue(pausedScope.any { it is Val.Num && it.rawDouble() == 21.0 }, pausedScope.toString())

        resume.countDown()
        worker.join(10_000)
        assertEquals("""{"a":42}""", (result as Right<*, *>).value().toString())
    }
}
