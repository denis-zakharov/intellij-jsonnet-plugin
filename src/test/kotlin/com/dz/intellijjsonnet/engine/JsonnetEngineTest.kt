package com.dz.intellijjsonnet.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonnetEngineTest {

    @Test
    fun `evaluates a simple object literal`() {
        val result = JsonnetEngine.evaluate("test.jsonnet", "local x = 1; { a: x, b: x + 1 }")
        require(result is JsonnetEngine.Result.Success) { "Expected success, got $result" }
        assertEquals(
            """
            {
              "a": 1,
              "b": 2
            }
            """.trimIndent(),
            result.json,
        )
    }

    @Test
    fun `reports parse errors instead of throwing`() {
        val result = JsonnetEngine.evaluate("bad.jsonnet", "{ a: ")
        assertTrue(result is JsonnetEngine.Result.Failure)
    }
}
