package io.github.denis_zakharov.jsonnettanka.engine

import io.github.denis_zakharov.jsonnettanka.engine.importer.VirtualFileImporter
import io.github.denis_zakharov.jsonnettanka.engine.extension.SjsonnetExtensions
import io.github.denis_zakharov.jsonnettanka.shaded.scala.collection.immutable.`Map$`
import io.github.denis_zakharov.jsonnettanka.shaded.scala.collection.immutable.Map as ScalaMap
import io.github.denis_zakharov.jsonnettanka.shaded.scala.util.Left
import io.github.denis_zakharov.jsonnettanka.shaded.scala.util.Right
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.DefaultParseCache
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Importer
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Interpreter
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Path
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.StringBuilderWriter
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.YamlRenderer
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.`YamlRenderer$`
import io.github.denis_zakharov.jsonnettanka.shaded.ujson.Str
import io.github.denis_zakharov.jsonnettanka.shaded.ujson.Value
import io.github.denis_zakharov.jsonnettanka.shaded.ujson.`package` as UJson
import com.intellij.openapi.vfs.VirtualFile

/**
 * Embeds the shaded `sjsonnet` interpreter with no external process — the
 * "fast tier" from the plan's two-tier evaluation strategy (§4.2). Ground
 * truth (`tk show`/`tk apply`, anything touching Helm/Kustomize) stays a
 * Phase 3/4 shell-out; this is pure-Jsonnet evaluation only.
 */
object JsonnetEngine {

    /**
     * An `--ext-*` / `--tla-*` value. `sjsonnet`'s Map-based `Interpreter` constructor treats every
     * value as Jsonnet *code* (`ext-code`/`tla-code`), so [isCode]`= false` (the `ext-str`/`tla-str`
     * flavor) is implemented by handing it a Jsonnet string literal of [text] instead.
     */
    data class VarValue(val text: String, val isCode: Boolean = false)

    enum class OutputFormat { JSON, YAML }

    sealed class Result {
        data class Success(val output: String, val locator: SourceLocator? = null) : Result()
        data class Failure(val message: String) : Result()
    }

    /** No import resolution — for evaluating a snippet that isn't backed by a real file. */
    fun evaluate(
        fileName: String,
        source: String,
        extVars: Map<String, VarValue> = emptyMap(),
        tlaVars: Map<String, VarValue> = emptyMap(),
        format: OutputFormat = OutputFormat.JSON,
    ): Result {
        val path = InMemoryPath(fileName, source)
        return evaluateWith(path, source, Importer.empty(), extVars, tlaVars, format)
    }

    /** Full evaluation of a real file, with imports resolved against the IDE's VFS. */
    fun evaluateFile(
        file: VirtualFile,
        extVars: Map<String, VarValue> = emptyMap(),
        tlaVars: Map<String, VarValue> = emptyMap(),
        format: OutputFormat = OutputFormat.JSON,
    ): Result {
        val path = VirtualFilePath(file)
        val source = path.readTextOrEmpty()
        return evaluateWith(path, source, VirtualFileImporter(), extVars, tlaVars, format)
    }

    private fun evaluateWith(
        path: Path,
        source: String,
        importer: Importer,
        extVars: Map<String, VarValue>,
        tlaVars: Map<String, VarValue>,
        format: OutputFormat,
    ): Result {
        val parseCache = DefaultParseCache()
        val settings = Interpreter.`$lessinit$greater$default$6`()
        val storePos = Interpreter.`$lessinit$greater$default$7`()
        val logger = Interpreter.`$lessinit$greater$default$8`()
        val std = SjsonnetExtensions.std
        val variableResolver = Interpreter.`$lessinit$greater$default$10`()

        val interpreter = Interpreter(
            toScalaMap(extVars),
            toScalaMap(tlaVars),
            path,
            importer,
            parseCache,
            settings,
            storePos,
            logger,
            std,
            variableResolver,
        )

        val rendered: Rendered = when (format) {
            OutputFormat.JSON -> renderJson(interpreter, source, path)
            OutputFormat.YAML -> renderYaml(interpreter, source, path)
        }
        return when (rendered) {
            is Rendered.Ok -> Result.Success(rendered.text, SourceLocator(interpreter, source, path))
            is Rendered.Err -> Result.Failure(rendered.message)
        }
    }

    private sealed class Rendered {
        data class Ok(val text: String) : Rendered()
        data class Err(val message: String) : Rendered()
    }

    private fun renderJson(interpreter: Interpreter, source: String, path: Path): Rendered =
        when (val either = interpreter.interpret(source, path)) {
            is Right<*, *> -> Rendered.Ok(UJson.write(either.value() as Value, 2, false, false))
            is Left<*, *> -> Rendered.Err(either.value() as String)
            else -> Rendered.Err("Unexpected evaluation result: $either")
        }

    /** `quoteKeys = false`: bare keys read like the YAML `tk show` prints, not the JSON-in-YAML sjsonnet's default emits. */
    private fun renderYaml(interpreter: Interpreter, source: String, path: Path): Rendered {
        val defaults = `YamlRenderer$`.`MODULE$`
        val out = StringBuilderWriter(1024)
        val renderer = YamlRenderer(out, defaults.`$lessinit$greater$default$2`(), false, defaults.`$lessinit$greater$default$4`())
        return when (val either = interpreter.interpret0(source, path, renderer)) {
            is Right<*, *> -> Rendered.Ok(out.builder.toString())
            is Left<*, *> -> Rendered.Err(either.value() as String)
            else -> Rendered.Err("Unexpected evaluation result: $either")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toScalaMap(map: Map<String, VarValue>): ScalaMap<String, String> {
        var result: ScalaMap<String, String> = `Map$`.`MODULE$`.empty()
        for ((key, value) in map) {
            val code = if (value.isCode) value.text else UJson.write(Str(value.text), -1, false, false)
            result = result.updated(key, code) as ScalaMap<String, String>
        }
        return result
    }
}
