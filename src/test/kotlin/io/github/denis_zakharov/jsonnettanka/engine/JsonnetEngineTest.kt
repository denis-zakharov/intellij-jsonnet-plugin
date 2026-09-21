package io.github.denis_zakharov.jsonnettanka.engine

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
            result.output,
        )
    }

    @Test
    fun `reports parse errors instead of throwing`() {
        val result = JsonnetEngine.evaluate("bad.jsonnet", "{ a: ")
        assertTrue(result is JsonnetEngine.Result.Failure)
    }

    private fun ok(result: JsonnetEngine.Result): String {
        require(result is JsonnetEngine.Result.Success) { "Expected success, got $result" }
        return result.output
    }

    @Test
    fun `ext-str values arrive as strings, ext-code values as evaluated code`() {
        val output = ok(
            JsonnetEngine.evaluate(
                "ext.jsonnet",
                "{ s: std.extVar('s'), n: std.extVar('n') }",
                extVars = mapOf(
                    "s" to JsonnetEngine.VarValue("say \"hi\" \\ there"),
                    "n" to JsonnetEngine.VarValue("1 + 2", isCode = true),
                ),
            ),
        )
        assertEquals("{\n  \"n\": 3,\n  \"s\": \"say \\\"hi\\\" \\\\ there\"\n}", output)
    }

    @Test
    fun `top-level-argument vars are applied to a function file`() {
        val output = ok(
            JsonnetEngine.evaluate(
                "tla.jsonnet",
                "function(name, count=1) { greeting: 'hi ' + name, count: count }",
                tlaVars = mapOf(
                    "name" to JsonnetEngine.VarValue("bob"),
                    "count" to JsonnetEngine.VarValue("2", isCode = true),
                ),
            ),
        )
        assertEquals("{\n  \"count\": 2,\n  \"greeting\": \"hi bob\"\n}", output)
    }

    @Test
    fun `a missing required TLA var is a failure, not a crash`() {
        val result = JsonnetEngine.evaluate("tla.jsonnet", "function(name) name")
        assertTrue(result is JsonnetEngine.Result.Failure, "got $result")
    }

    @Test
    fun `YAML output renders nested objects and arrays`() {
        val output = ok(
            JsonnetEngine.evaluate(
                "y.jsonnet",
                "{ a: 1, b: { c: [1, 2], d: 'x' } }",
                format = JsonnetEngine.OutputFormat.YAML,
            ),
        )
        assertEquals("a: 1\nb:\n  c:\n  - 1\n  - 2\n  d: \"x\"", output)
    }

    @Test
    fun `YAML output reports evaluation errors the same way JSON does`() {
        val result = JsonnetEngine.evaluate("y.jsonnet", "{ a: error 'boom' }", format = JsonnetEngine.OutputFormat.YAML)
        assertTrue(result is JsonnetEngine.Result.Failure && "boom" in result.message, "got $result")
    }
}
