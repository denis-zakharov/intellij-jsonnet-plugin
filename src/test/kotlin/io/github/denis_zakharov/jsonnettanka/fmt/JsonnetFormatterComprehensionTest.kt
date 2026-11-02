package io.github.denis_zakharov.jsonnettanka.fmt

import io.github.denis_zakharov.jsonnettanka.engine.JsonnetEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Comprehensions and computed field names survive the formatter: stable on a second pass and same evaluation result. */
class JsonnetFormatterComprehensionTest {
    private val source = """
        local arr = std.range(5, 8);
        {
        [ 'a'+'b' ]: [x+1 for x in arr if x%2==0 for y in arr],
          ['c'+'d']::{ local y=x*2, [ 'k'+x ]:y for x in arr },
            [std.join('',[c for c in ['u','v']])](p,q=1):: p+q,
        }
    """.trimIndent()

    @Test
    fun `formatting is stable and does not change the result`() {
        val once = JsonnetFormatter.format(source)
        assertEquals(once, JsonnetFormatter.format(once), once)
        val before = JsonnetEngine.evaluate("a.jsonnet", source)
        val after = JsonnetEngine.evaluate("a.jsonnet", once)
        val a = before as JsonnetEngine.Result.Success
        val b = after as JsonnetEngine.Result.Success
        assertEquals(a.output, b.output, once)
    }

    @Test
    fun `formats computed keys and comprehension clauses with jsonnetfmt spacing`() {
        val out = JsonnetFormatter.format(source)
        assert(out.contains("['a' + 'b']: [x + 1 for x in arr if x % 2 == 0 for y in arr],")) { out }
        assert(out.contains("['c' + 'd']:: {")) { out }
        assert(out.contains("[std.join('', [c for c in ['u', 'v']])](p, q=1):: p + q,")) { out }
    }

    @Test
    fun `formats a merge-operator object comprehension like jsonnetfmt`() {
        // `jsonnetfmt` output for the same input (go-jsonnet v0.22.0)
        assertEquals(
            "{ [key]+: { f1: 'v1' } for key in ['a', 'b'] }\n",
            JsonnetFormatter.format("{[key]+:{f1:'v1'} for key in ['a','b']}"),
        )
    }
}
