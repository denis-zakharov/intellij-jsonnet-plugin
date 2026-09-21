package io.github.denis_zakharov.jsonnettanka.engine.extension

import io.github.denis_zakharov.jsonnettanka.engine.JsonnetEngine
import io.github.denis_zakharov.jsonnettanka.stdlib.StdLibRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Expected values were confirmed against the real `jsonnet` binary (go-jsonnet v0.22.0). */
class StdExtrasTest {

    private fun evalJson(expr: String): String {
        val result = JsonnetEngine.evaluate("extras.jsonnet", expr)
        require(result is JsonnetEngine.Result.Success) { "Expected success for `$expr`, got $result" }
        return result.output
    }

    @Test
    fun `std id exists`() {
        assertEquals("[\n  1,\n  \"a\"\n]", evalJson("[std.id(1), std.id(x='a')]"))
    }

    @Test
    fun `std id is offered by std completion`() {
        assertTrue("id" in StdLibRegistry.memberNames)
    }

    @Test
    fun `go-jsonnet parameter names work as named arguments`() {
        assertEquals("5", evalJson("std.hypot(x=3, y=4)"))
        assertEquals("1", evalJson("std.modulo(x=7, y=3)"))
        assertEquals("[\n  3,\n  2,\n  1\n]", evalJson("std.reverse(arr=[1, 2, 3])"))
        assertEquals("true", evalJson("std.isNull(x=null)"))
        assertEquals("true", evalJson("std.equals(x=[1], y=[1])"))
        assertEquals("[\n  1,\n  3\n]", evalJson("std.removeAt(arr=[1, 2, 3], i=1)"))
        assertEquals("true", evalJson("std.objectHasEx(obj={ a: 1 }, fname='a', hidden=true)"))
        assertEquals("[\n  \"a\"\n]", evalJson("std.objectFieldsEx(obj={ a: 1, b:: 2 }, hidden=false)"))
        assertEquals("\"8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4\"", evalJson("std.sha256(s='hi')"))
        assertEquals("\"&lt;\"", evalJson("std.escapeStringXML(str_='<')"))
        assertEquals("\"1\"", evalJson("std.manifestJson(value=1)"))
    }

    @Test
    fun `positional calls keep working after the rename`() {
        assertEquals("5", evalJson("std.hypot(3, 4)"))
        assertEquals("[\n  2,\n  1\n]", evalJson("std.reverse([1, 2])"))
    }

    @Test
    fun `every alias table entry targets a real sjsonnet builtin of matching arity`() {
        // If sjsonnet renames/re-arities one of these on upgrade, `rename` silently skips it; catch that here.
        val builtinNames = StdLibRegistry.memberNames
        for ((name, params) in StdExtras.goParameterNames) {
            assertTrue(name in builtinNames, "std.$name no longer exists in sjsonnet")
            val call = "std.$name(${params.joinToString(", ") { "$it=null" }})"
            val result = JsonnetEngine.evaluate("alias.jsonnet", call)
            // Whatever the value error is, it must not be "no parameter" — that means the alias didn't apply.
            if (result is JsonnetEngine.Result.Failure) {
                assertTrue(!result.message.contains("no parameter", ignoreCase = true), "$call: ${result.message}")
            }
        }
    }
}
