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
    fun `evaluates digit separators`() {
        assertEquals("1000", ok(JsonnetEngine.evaluate("sep.jsonnet", "1_000")))
    }

    @Test
    fun `evaluates the tutorial array and object comprehensions`() {
        val out = ok(
            JsonnetEngine.evaluate(
                "comp.jsonnet",
                """
                local arr = std.range(5, 8);
                {
                  higher: [x + 1 for x in arr],
                  evens_and_odds: ["%d-%d" % [x, y] for x in arr if x % 2 == 0 for y in arr if y % 2 == 1],
                  obj: { ["foo" + x]: true for x in [1, 2] },
                  with_local: { local y = x * 2, ["k" + x]: y for x in [1, 2] },
                }
                """.trimIndent(),
            ),
        )
        assertTrue(out.contains("\"foo1\": true"), out)
        assertTrue(out.contains("\"k2\": 4"), out)
        assertTrue(out.contains("\"6-5\""), out)
    }

    @Test
    fun `evaluates computed field names alone and with comprehensions`() {
        val out = ok(
            JsonnetEngine.evaluate(
                "computed.jsonnet",
                """
                local k = 'a';
                {
                  [k + 'b']: 1,
                  [k + 'h']:: 2,
                  [k + 'p']+: { x: 1 },
                  [k + 'f'](p, q=1):: p + q,
                  [k]: 3,
                  [if true then 'cond' else 'no']: 4,
                  nested: { [x]: { [y]: x + y for y in ['1', '2'] } for x in ['p', 'q'] },
                  keys_from_comp: { [std.join('', [c for c in ['u', 'v']])]: 5 },
                  localized: { local y = x + '!', [x]: y for x in ['hi'] },
                  usesF: self.af(1),
                } + { ap+: { y: 2 } }
                """.trimIndent(),
            ),
        )
        listOf(
            "\"ab\": 1", "\"a\": 3", "\"cond\": 4", "\"uv\": 5", "\"hi\": \"hi!\"",
            "\"usesF\": 2", "\"y\": 2",
        ).forEach { assertTrue(out.contains(it), "missing $it in\n$out") }
        assertTrue(!out.contains("\"ah\""), "hidden computed field leaked:\n$out")
        assertTrue(out.contains("\"p\": {") && out.contains("\"p1\"".let { "\"1\": \"p1\"" }), out)
    }

    @Test
    fun `evaluates an object comprehension with the merge operator`() {
        val out = ok(
            JsonnetEngine.evaluate(
                "merge.jsonnet",
                "{ a: { x: 1 } } + { [key]+: { f1: 'v1' } for key in ['a', 'b'] }",
            ),
        )
        assertTrue(out.contains("\"x\": 1") && out.contains("\"f1\": \"v1\"") && out.contains("\"b\""), out)
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
