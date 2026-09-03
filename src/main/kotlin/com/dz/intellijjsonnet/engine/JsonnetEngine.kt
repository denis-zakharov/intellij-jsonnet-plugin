package com.dz.intellijjsonnet.engine

import com.dz.intellijjsonnet.shaded.sjsonnet.DefaultParseCache
import com.dz.intellijjsonnet.shaded.sjsonnet.Importer
import com.dz.intellijjsonnet.shaded.sjsonnet.Interpreter
import com.dz.intellijjsonnet.shaded.scala.collection.immutable.`Map$`
import com.dz.intellijjsonnet.shaded.scala.util.Left
import com.dz.intellijjsonnet.shaded.scala.util.Right
import com.dz.intellijjsonnet.shaded.ujson.Value
import com.dz.intellijjsonnet.shaded.ujson.`package` as UJson

/**
 * Phase 0 spike: embeds the shaded `sjsonnet` interpreter with no external process.
 * No import resolution yet (backed by [Importer.empty]) — the VFS/PSI-backed importer
 * described in the plan (§4.4) lands in Phase 1+.
 */
object JsonnetEngine {

    sealed class Result {
        data class Success(val json: String) : Result()
        data class Failure(val message: String) : Result()
    }

    fun evaluate(fileName: String, source: String): Result {
        val emptyMap = `Map$`.`MODULE$`.empty<String, String>()
        val path = InMemoryPath(fileName, source)
        val importer: Importer = Importer.empty()
        val parseCache = DefaultParseCache()
        val settings = Interpreter.`$lessinit$greater$default$6`()
        val storePos = Interpreter.`$lessinit$greater$default$7`()
        val logger = Interpreter.`$lessinit$greater$default$8`()
        val std = Interpreter.`$lessinit$greater$default$9`()
        val variableResolver = Interpreter.`$lessinit$greater$default$10`()

        val interpreter = Interpreter(
            emptyMap,
            emptyMap,
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
}
