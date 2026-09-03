package com.dz.intellijjsonnet.engine

import com.dz.intellijjsonnet.engine.importer.VirtualFileImporter
import com.dz.intellijjsonnet.shaded.scala.collection.immutable.`Map$`
import com.dz.intellijjsonnet.shaded.scala.collection.immutable.Map as ScalaMap
import com.dz.intellijjsonnet.shaded.scala.util.Left
import com.dz.intellijjsonnet.shaded.scala.util.Right
import com.dz.intellijjsonnet.shaded.sjsonnet.DefaultParseCache
import com.dz.intellijjsonnet.shaded.sjsonnet.Importer
import com.dz.intellijjsonnet.shaded.sjsonnet.Interpreter
import com.dz.intellijjsonnet.shaded.sjsonnet.Path
import com.dz.intellijjsonnet.shaded.ujson.Value
import com.dz.intellijjsonnet.shaded.ujson.`package` as UJson
import com.intellij.openapi.vfs.VirtualFile

/**
 * Embeds the shaded `sjsonnet` interpreter with no external process — the
 * "fast tier" from the plan's two-tier evaluation strategy (§4.2). Ground
 * truth (`tk show`/`tk apply`, anything touching Helm/Kustomize) stays a
 * Phase 3/4 shell-out; this is pure-Jsonnet evaluation only.
 */
object JsonnetEngine {

    sealed class Result {
        data class Success(val json: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /** No import resolution — for evaluating a snippet that isn't backed by a real file. */
    fun evaluate(fileName: String, source: String): Result {
        val path = InMemoryPath(fileName, source)
        return evaluateWith(path, source, Importer.empty(), emptyMap(), emptyMap())
    }

    /** Full evaluation of a real file, with imports resolved against the IDE's VFS. */
    fun evaluateFile(
        file: VirtualFile,
        extVars: Map<String, String> = emptyMap(),
        tlaVars: Map<String, String> = emptyMap(),
    ): Result {
        val path = VirtualFilePath(file)
        val source = path.readTextOrEmpty()
        return evaluateWith(path, source, VirtualFileImporter(), extVars, tlaVars)
    }

    private fun evaluateWith(
        path: Path,
        source: String,
        importer: Importer,
        extVars: Map<String, String>,
        tlaVars: Map<String, String>,
    ): Result {
        val parseCache = DefaultParseCache()
        val settings = Interpreter.`$lessinit$greater$default$6`()
        val storePos = Interpreter.`$lessinit$greater$default$7`()
        val logger = Interpreter.`$lessinit$greater$default$8`()
        val std = Interpreter.`$lessinit$greater$default$9`()
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

        return when (val either = interpreter.interpret(source, path)) {
            is Right<*, *> -> {
                val value = either.value() as Value
                Result.Success(UJson.write(value, 2, false, false))
            }
            is Left<*, *> -> Result.Failure(either.value() as String)
            else -> Result.Failure("Unexpected evaluation result: $either")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toScalaMap(map: Map<String, String>): ScalaMap<String, String> {
        var result: ScalaMap<String, String> = `Map$`.`MODULE$`.empty()
        for ((key, value) in map) {
            result = result.updated(key, value) as ScalaMap<String, String>
        }
        return result
    }
}
