package io.github.denis_zakharov.jsonnettanka.engine.extension

import io.github.denis_zakharov.jsonnettanka.engine.JsonnetEngine
import io.github.denis_zakharov.jsonnettanka.shaded.ujson.Str
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every expected value here is the verbatim output of a real `tk eval` (Tanka v0.39, go-jsonnet
 * v0.22) for the same expression, not something derived from our implementation — that is the point
 * of the test. Re-derive one with `tk eval` in any directory containing a `jsonnetfile.json`.
 */
class TankaNativesTest {

    private fun evalJson(expr: String): String {
        val result = JsonnetEngine.evaluate("natives.jsonnet", expr)
        require(result is JsonnetEngine.Result.Success) { "Expected success for `$expr`, got $result" }
        return result.output
    }

    /** Evaluates a string-valued expression and returns the string itself (not its JSON encoding). */
    private fun evalString(expr: String): String = (GoFormats.parseJson(evalJson(expr)) as Str).value()

    private fun failureOf(expr: String): String {
        val result = JsonnetEngine.evaluate("natives.jsonnet", expr)
        require(result is JsonnetEngine.Result.Failure) { "Expected failure for `$expr`, got $result" }
        return result.message
    }

    private fun yaml(json: String) = evalString("std.native('manifestYamlFromJson')(std.manifestJson($json))")

    private fun lines(vararg l: String) = l.joinToString("\n") + "\n"

    @Test
    fun `parseJson`() {
        assertEquals(
            "{\n  \"a\": [\n    true,\n    null\n  ],\n  \"z\": 1.5\n}",
            evalJson("""std.native('parseJson')('{"z":1.5,"a":[true,null]}')"""),
        )
    }

    @Test
    fun `parseYaml always returns an array of documents`() {
        assertEquals("[\n  {\n    \"a\": 1\n  }\n]", evalJson("std.native('parseYaml')('a: 1')"))
        assertEquals("[\n  {\n    \"a\": 1\n  },\n  {\n    \"b\": 2\n  }\n]", evalJson("std.native('parseYaml')('a: 1\\n---\\nb: 2\\n')"))
        assertEquals("[]", evalJson("std.native('parseYaml')('')"))
        // a lone document that is itself an array must still be wrapped
        assertEquals("[\n  [\n    1,\n    2\n  ]\n]", evalJson("std.native('parseYaml')('- 1\\n- 2')"))
    }

    @Test
    fun `parseYaml scalar resolution matches yaml v3`() {
        // yaml.v3 (YAML 1.2 core): `yes` stays a string; hex ints and exponent floats are numbers.
        val out = evalJson("std.native('parseYaml')('i: 0x10\\nf: 1.5e3\\nb: yes\\nnul: ~\\nt: 2001-12-14\\non: off\\n')")
        assertTrue(out.contains("\"b\": \"yes\""), out)
        assertTrue(out.contains("\"i\": 16"), out)
        assertTrue(out.contains("\"f\": 1500"), out)
        assertTrue(out.contains("\"nul\": null"), out)
        // yaml.v3 hands timestamps to encoding/json as time.Time, which marshals as RFC 3339
        assertTrue(out.contains("\"t\": \"2001-12-14T00:00:00Z\""), out)
        // ...and GitHub-Actions-style `on:` must not become the boolean key `true`
        assertTrue(out.contains("\"on\": \"off\""), out)
    }

    @Test
    fun `manifestJsonFromJson reindents text and keeps key order`() {
        assertEquals(
            "{\n  \"b\": [\n    1,\n    2\n  ],\n  \"a\": {}\n}\n",
            evalString("""std.native('manifestJsonFromJson')('{"b":[1,2],"a":{}}', 2)"""),
        )
        assertEquals(
            lines("[", "    1,", "    {", "        \"a\": [],", "        \"b\": {},", "        \"c\": \"x y\"", "    },", "    []", "]"),
            evalString("""std.native('manifestJsonFromJson')('  [1,{"a":[],"b":{},"c":"x y"},[ ]]  ', 4)"""),
        )
        // number spelling is preserved (text is re-indented, not re-serialized); indent 0 still breaks lines
        assertEquals("{\n\"z\": 1.50,\n\"a\": 1e3\n}\n", evalString("""std.native('manifestJsonFromJson')('{"z":1.50,"a":1e3}', 0)"""))
    }

    @Test
    fun `manifestYamlFromJson matches yaml v3 layout`() {
        assertEquals(
            lines(
                "a:", "    c: 'x: y'", "    e: \"\"", "    \"n\": \"123\"", "    t: \"true\"",
                "b:", "    - 1", "    - 2", "    - k: v", "      z:", "        - q",
                "emptyL: []", "emptyM: {}", "f: 1.5", "nul: null", "s: |", "    multi", "    line",
            ),
            yaml("""{ b: [1, 2, { k: 'v', z: ['q'] }], a: { c: 'x: y', t: 'true', n: '123', e: '' }, s: 'multi\nline\n', f: 1.5, nul: null, emptyL: [], emptyM: {} }"""),
        )
        // yaml.v3 nests inside a sequence item at +2, then back to +4 — an easy thing to get subtly wrong
        assertEquals(
            lines(
                "x:", "    - a:", "        b:", "            c: 1", "      l:", "        - - 1", "          - 2", "        - - 3",
                "      m:", "        - p: 1",
            ),
            yaml("{ x: [{ a: { b: { c: 1 } }, l: [[1, 2], [3]], m: [{ p: 1 }] }] }"),
        )
        assertEquals(lines("- 1", "- - 2", "  - 3", "- k: v", "- []"), yaml("[1, [2, 3], { k: 'v' }, []]"))
    }

    @Test
    fun `manifestYamlFromJson quotes strings the way yaml v3 does`() {
        assertEquals(
            lines(
                "a: \"null\"", "b: \"yes\"", "c: \"1.5\"", "d: a#b", "e: ' lead'", "f: '-'", "g: has \"q\"", "h: it's",
                "i: \"tab\\there\"", "j: \"\"", "k: é", "l: \"0x1f\"", "m: 'a: b'", "\"n\": '@x'", "o: '- x'", "p: \"y\"",
                "q: \"no\"", "r: \"~\"", "s: ok",
            ),
            yaml(
                """{ a: 'null', b: 'yes', c: '1.5', d: 'a#b', e: ' lead', f: '-', g: 'has "q"', h: "it's", i: 'tab\there', j: '',
                   k: 'é', l: '0x1f', m: 'a: b', n: '@x', o: '- x', p: 'y', q: 'no', r: '~', s: 'ok' }""",
            ),
        )
    }

    @Test
    fun `manifestYamlFromJson formats numbers like go g format`() {
        assertEquals(
            lines("a: 1e+21", "b: 0.1", "c: -3", "d: 1e-07", "e: 1e+08", "f: 1e+15"),
            yaml("{ a: 1e21, b: 0.1, c: -3, d: 1e-7, e: 100000000, f: 1.0e15 }"),
        )
        assertEquals("123456", GoFormats.formatFloat(123456.0))
        assertEquals("1.234567e+06", GoFormats.formatFloat(1234567.0))
    }

    @Test
    fun `manifestYamlFromJson sorts keys with yaml v3's natural ordering`() {
        assertEquals(
            lines("_x: 6", "\"1\": 7", "\"2\": 9", "\"10\": 8", "B: 3", "Z9z: 10", "a: 5", "a1: 12", "a01: 11", "a2: 1", "a10: 2", "b: 4"),
            yaml("{ a2: 1, a10: 2, B: 3, b: 4, a: 5, _x: 6, '1': 7, '10': 8, '2': 9, Z9z: 10, a01: 11, a1: 12 }"),
        )
    }

    @Test
    fun `regex natives`() {
        assertEquals("a\\.b\\*c", evalString("std.native('escapeStringRegex')('a.b*c')"))
        assertEquals("true", evalJson("std.native('regexMatch')('b', 'abc')"))
        assertEquals("false", evalJson("std.native('regexMatch')('^b', 'abc')"))
        assertEquals("baxbax", evalString("std.native('regexSubst')('(a)(b)', 'abab', '\$2\${1}x')"))
        assertEquals("<>-<xx>", evalString("std.native('regexSubst')('a(x*)b', 'ab-axxb', '<\$1>')"))
        assertEquals("me_at_me!you", evalString("std.native('regexSubst')('(?P<w>\\\\w+)@', 'me@you', '\${w}_at_\$w!')"))
        assertEquals("-a-b-c-", evalString("std.native('regexSubst')('x*', 'abc', '-')"))
        // Go: `$1x` names a (non-existent) group `1x`; `\` is literal; `$$` is `$`; a trailing `$` is literal
        assertEquals("|ax|\$|\\1||\$", evalString("std.native('regexSubst')('(a)', 'a', '\$1x|\${1}x|\$\$|\\\\1|\$9|\$')"))
    }

    @Test
    fun `sha256`() {
        assertEquals("8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4", evalString("std.native('sha256')('hi')"))
    }

    @Test
    fun `invalid input is a Jsonnet error, not a crash`() {
        assertTrue(failureOf("std.native('regexMatch')('(', 'x')").contains("missing closing )"))
        assertTrue(failureOf("std.native('manifestJsonFromJson')('{bad', 2)").isNotBlank())
        assertTrue(failureOf("std.native('parseYaml')('a: [')").contains("parsing yaml"))
        assertTrue(failureOf("std.native('parseJson')('{')").isNotBlank())
    }

    @Test
    fun `every native offered by completion is actually implemented`() {
        for (name in io.github.denis_zakharov.jsonnettanka.tanka.TankaNativeFunctions.names) {
            assertEquals("true", evalJson("std.native('$name') != null"), "std.native('$name') is offered but not implemented")
        }
    }

    @Test
    fun `unregistered natives stay null, like plain Jsonnet`() {
        assertEquals("null", evalJson("std.native('helmTemplate')"))
        assertEquals("null", evalJson("std.native('nonexistent')"))
    }

    @Test
    fun `natives accept named arguments with Tanka's parameter names`() {
        assertEquals("true", evalJson("std.native('regexMatch')(regex='b', string='abc')"))
        assertEquals("x", evalString("std.native('regexSubst')(regex='a', src='a', repl='x')"))
    }
}
