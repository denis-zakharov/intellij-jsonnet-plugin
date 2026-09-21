package io.github.denis_zakharov.jsonnettanka.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins down where sjsonnet's own positions land for each shape of source, since click-jump is only
 * as good as `Val.Obj._sourceMemberList`/`Expr.Member.Field.pos` actually pointing at the key text.
 */
class SourceLocatorTest {

    private fun locate(source: String, vararg segments: Any): SourceLocation? {
        val result = JsonnetEngine.evaluate("t.jsonnet", source)
        require(result is JsonnetEngine.Result.Success) { "Expected success, got $result" }
        val path = segments.map { if (it is Int) PathSegment.Index(it) else PathSegment.Key(it as String) }
        return result.locator!!.locate(path)
    }

    /** Offset of [needle] in [source], skipping [skip] earlier occurrences. */
    private fun at(source: String, needle: String, skip: Int = 0): Int {
        var idx = -1
        repeat(skip + 1) { idx = source.indexOf(needle, idx + 1) }
        return idx
    }

    @Test
    fun `a scalar lands on the literal that produced it, a nested object on its brace`() {
        val src = "{ a: 1, b: { c: 2 } }"
        assertEquals(at(src, "2"), locate(src, "b", "c")!!.offset)
        assertEquals(at(src, "{ c"), locate(src, "b")!!.offset)
    }

    @Test
    fun `array elements and fields inside them`() {
        val src = "{ xs: [1, { y: 3 }] }"
        assertEquals(at(src, "3"), locate(src, "xs", 1, "y")!!.offset)
        assertEquals(at(src, "{ y"), locate(src, "xs", 1)!!.offset)
        assertEquals(at(src, "1"), locate(src, "xs", 0)!!.offset)
    }

    @Test
    fun `an overriding field in a composition resolves to the value that won`() {
        val src = "{ a: 1 } + { a: 2 }"
        assertEquals(at(src, "2"), locate(src, "a")!!.offset)
    }

    @Test
    fun `an inherited field resolves into the base object`() {
        val src = "local base = { k: 1 }; base + { z: 2 }"
        assertEquals(at(src, "1"), locate(src, "k")!!.offset)
        assertEquals(at(src, "2"), locate(src, "z")!!.offset)
    }

    @Test
    fun `a value that comes from a local lands where the local's value was written`() {
        val src = "local v = 5; { x: v }"
        assertEquals(at(src, "5"), locate(src, "x")!!.offset)
    }

    @Test
    fun `a value produced by a function call lands on the expression that made it`() {
        val src = "local mk(n) = { [n]: 1, fixed: n + '!' }; { r: mk('q') }"
        // `n + '!'` is an operator expression: the value's position is inside that expression.
        val expr = "n + '!'"
        val offset = locate(src, "r", "fixed")!!.offset
        assertEquals(true, offset in at(src, expr)..at(src, expr) + expr.length, "offset $offset")
    }

    @Test
    fun `comprehension-computed keys are ordinary keys`() {
        val src = "{ [k]: 1 for k in ['a', 'b'] }"
        assertEquals(at(src, "1"), locate(src, "b")!!.offset)
    }

    @Test
    fun `a path that leaves the evaluated structure stops at the deepest reachable value`() {
        val src = "{ a: { b: 1 } }"
        assertEquals(at(src, "{ b"), locate(src, "a", "nope", "deeper")!!.offset)
    }

    @Test
    fun `the empty path is the root value`() {
        val src = "  { a: 1 }"
        assertEquals(at(src, "{"), locate(src)!!.offset)
    }
}
