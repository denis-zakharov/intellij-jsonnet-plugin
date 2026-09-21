package com.dz.intellijjsonnet.stdlib

import com.dz.intellijjsonnet.engine.JsonnetEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StdLibExamplesTest {

    @Test
    fun `every std member has an example and no example is stale`() {
        val members = StdLibRegistry.memberNames.toSet()
        val documented = StdLibExamples.examples.keys
        assertEquals(emptySet<String>(), members - documented, "std members without a hover example")
        assertEquals(emptySet<String>(), documented - members, "examples for members sjsonnet no longer has")
    }

    @Test
    fun `every example evaluates to the result it claims`() {
        val failures = StdLibExamples.examples.mapNotNull { (name, example) ->
            val expected = example.result ?: return@mapNotNull null
            val outcome = JsonnetEngine.evaluate("example.jsonnet", "(${example.code}) == ($expected)")
            when {
                outcome is JsonnetEngine.Result.Failure -> "std.$name: ${outcome.message.lineSequence().first()}"
                (outcome as JsonnetEngine.Result.Success).output.trim() != "true" -> {
                    val actual = JsonnetEngine.evaluate("actual.jsonnet", example.code)
                    "std.$name: `${example.code}` is ${(actual as? JsonnetEngine.Result.Success)?.output?.replace("\n", " ")}, not $expected"
                }
                else -> null
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `each example exercises the function it documents`() {
        for ((name, example) in StdLibExamples.examples) {
            assertTrue("std.$name" in example.code, "the example for $name should call std.$name: ${example.code}")
        }
    }

    @Test
    fun `parameters come from the engine, with optional ones marked`() {
        assertEquals(listOf("sep", "arr"), StdLibRegistry.parameters("join")!!.map { it.name })
        assertEquals(listOf(false, true), StdLibRegistry.parameters("sort")!!.map { it.optional })
        assertEquals(null, StdLibRegistry.parameters("pi"))
        assertEquals(
            emptyList<String>(),
            StdLibRegistry.memberNames.filter { it != "pi" && it != "thisFile" && StdLibRegistry.parameters(it) == null },
        )
    }
}
