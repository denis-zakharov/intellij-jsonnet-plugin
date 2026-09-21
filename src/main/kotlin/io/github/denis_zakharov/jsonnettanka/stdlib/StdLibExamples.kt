package io.github.denis_zakharov.jsonnettanka.stdlib

/**
 * A usage snippet shown in the hover for a `std` function. [code] is a Jsonnet expression; [result], when
 * present, is a Jsonnet expression for the value it produces (shown as `// => result`).
 *
 * Every example with a [result] is evaluated by `StdLibExamplesTest` as `(code) == (result)`, so the
 * hover can't claim something the pinned sjsonnet doesn't do. Those without one depend on the environment
 * (an ext var, the evaluated file's path) and are shown as plain usage. [note] is a caveat shown under the
 * snippet, for what the plugin's engine does that real Tanka doesn't (`docs/sjsonnet-gaps.md`).
 *
 * The results were also checked against go-jsonnet v0.22.0 (`jsonnet -e`) and, for `std.native`, `tk eval`,
 * since that — not sjsonnet — is what runs the code in production.
 */
data class StdLibExample(val code: String, val result: String? = null, val note: String? = null)

/**
 * Hand-written on purpose: signatures come from the engine (see [StdLibRegistry.parameters]) but a
 * usage example can't. The test also requires an entry for every `std` member, so a version bump that adds
 * a function fails until it gets one.
 */
object StdLibExamples {

    private const val NOT_IN_GO_JSONNET =
        "Not in go-jsonnet, so this fails under `tk` and `jsonnet`. Tanka provides "

    fun forName(name: String): StdLibExample? = examples[name]

    internal val examples: Map<String, StdLibExample> = buildMap {
        fun ex(name: String, code: String, result: String? = null, note: String? = null) {
            put(name, StdLibExample(code, result, note))
        }

        // -- types and inspection --
        ex("type", "std.type([])", "'array'")
        ex("length", "std.length([1, 2, 3])", "3")
        ex("isArray", "std.isArray([1])", "true")
        ex("isBoolean", "std.isBoolean(false)", "true")
        ex("isDecimal", "std.isDecimal(1.5)", "true")
        ex("isEmpty", "std.isEmpty('')", "true")
        ex("isEven", "std.isEven(4)", "true")
        ex("isFunction", "std.isFunction(function(x) x)", "true")
        ex("isInteger", "std.isInteger(3)", "true")
        ex("isNull", "std.isNull(null)", "true")
        ex("isNumber", "std.isNumber(1)", "true")
        ex("isObject", "std.isObject({})", "true")
        ex("isOdd", "std.isOdd(3)", "true")
        ex("isString", "std.isString('a')", "true")
        ex("id", "std.id(42)", "42")
        ex("equals", "std.equals({ a: [1] }, { a: [1] })", "true")
        ex("primitiveEquals", "std.primitiveEquals('a', 'a')", "true")
        ex("assertEqual", "std.assertEqual(1 + 1, 2)", "true")
        ex("trace", "std.trace('debugging', 42)", "42")
        ex("extVar", "std.extVar('env')")
        ex("native", "std.native('sha256')('abc')", "'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'")
        ex("thisFile", "std.thisFile")
        ex("resolvePath", "std.resolvePath('lib/a/main.libsonnet', 'b.libsonnet')", "'lib/a/b.libsonnet'")

        // -- math --
        ex("pi", "std.pi", "3.141592653589793")
        ex("abs", "std.abs(-3)", "3")
        ex("sign", "std.sign(-5)", "-1")
        ex("max", "std.max(1, 2)", "2")
        ex("min", "std.min(1, 2)", "1")
        ex("clamp", "std.clamp(15, 0, 10)", "10")
        ex("mod", "std.mod(7, 3)", "1")
        ex("modulo", "std.modulo(-7, 3)", "-1")
        ex("pow", "std.pow(2, 10)", "1024")
        ex("sqrt", "std.sqrt(16)", "4")
        ex("hypot", "std.hypot(3, 4)", "5")
        ex("floor", "std.floor(1.8)", "1")
        ex("ceil", "std.ceil(1.2)", "2")
        ex("round", "std.round(2.5)", "3")
        ex("exp", "std.exp(0)", "1")
        ex("log", "std.log(1)", "0")
        ex("log2", "std.log2(8)", "3")
        ex("log10", "std.log10(100)", "2")
        ex("exponent", "std.exponent(8)", "4")
        ex("mantissa", "std.mantissa(8)", "0.5")
        ex("sin", "std.sin(0)", "0")
        ex("cos", "std.cos(0)", "1")
        ex("tan", "std.tan(0)", "0")
        ex("asin", "std.asin(0)", "0")
        ex("acos", "std.acos(1)", "0")
        ex("atan", "std.atan(0)", "0")
        ex("atan2", "std.atan2(0, 1)", "0")
        ex("deg2rad", "std.deg2rad(180)", "std.pi")
        ex("rad2deg", "std.rad2deg(std.pi)", "180")
        ex("xor", "std.xor(true, false)", "true")
        ex("xnor", "std.xnor(true, true)", "true")

        // -- strings --
        ex("toString", "std.toString({ a: 1 })", "'{\"a\": 1}'")
        ex("format", "std.format('%s has %d replicas', ['app', 3])", "'app has 3 replicas'")
        ex("substr", "std.substr('hello', 1, 3)", "'ell'")
        ex("split", "std.split('a,b,c', ',')", "['a', 'b', 'c']")
        ex("splitLimit", "std.splitLimit('a,b,c', ',', 1)", "['a', 'b,c']")
        ex("splitLimitR", "std.splitLimitR('a,b,c', ',', 1)", "['a,b', 'c']")
        ex("join", "std.join(', ', ['a', 'b', 'c'])", "'a, b, c'")
        ex("lines", "std.lines(['a', 'b'])", "'a\\nb\\n'")
        ex("deepJoin", "std.deepJoin(['a', ['b', ['c']]])", "'abc'")
        ex("repeat", "std.repeat('ab', 3)", "'ababab'")
        ex("strReplace", "std.strReplace('a-b-c', '-', '_')", "'a_b_c'")
        ex("stringChars", "std.stringChars('abc')", "['a', 'b', 'c']")
        ex("startsWith", "std.startsWith('kubernetes', 'kube')", "true")
        ex("endsWith", "std.endsWith('main.jsonnet', '.jsonnet')", "true")
        ex("findSubstr", "std.findSubstr('ab', 'abcab')", "[0, 3]")
        ex("asciiLower", "std.asciiLower('HeLLo')", "'hello'")
        ex("asciiUpper", "std.asciiUpper('HeLLo')", "'HELLO'")
        ex("equalsIgnoreCase", "std.equalsIgnoreCase('Tanka', 'TANKA')", "true")
        ex("trim", "std.trim('  hi  ')", "'hi'")
        ex("stripChars", "std.stripChars('xxabcxx', 'x')", "'abc'")
        ex("lstripChars", "std.lstripChars('xxabc', 'x')", "'abc'")
        ex("rstripChars", "std.rstripChars('abcxx', 'x')", "'abc'")
        ex("char", "std.char(65)", "'A'")
        ex("codepoint", "std.codepoint('A')", "65")
        ex("parseInt", "std.parseInt('-42')", "-42")
        ex("parseHex", "std.parseHex('ff')", "255")
        ex("parseOctal", "std.parseOctal('755')", "493")
        ex("encodeUTF8", "std.encodeUTF8('hi')", "[104, 105]")
        ex("decodeUTF8", "std.decodeUTF8([104, 105])", "'hi'")
        ex("base64", "std.base64('hello')", "'aGVsbG8='")
        ex("base64Decode", "std.base64Decode('aGVsbG8=')", "'hello'")
        ex("base64DecodeBytes", "std.base64DecodeBytes('aGVsbG8=')", "[104, 101, 108, 108, 111]")
        ex("md5", "std.md5('a')", "'0cc175b9c0f1b6a831c399e269772661'")
        ex("sha1", "std.sha1('a')", "'86f7e437faa5a7fce15d1ddcb9eaeaea377667b8'")
        ex("sha256", "std.sha256('a')", "'ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb'")
        ex(
            "sha512", "std.sha512('a')",
            "'1f40fc92da241694750979ee6cf582f2d5d7d28e18335de05abc54d0560e0f5302860c652bf08d560252aa5e74210546f369fbbbce8c12cfc7957b2652fe9a75'",
        )
        ex(
            "sha3", "std.sha3('a')",
            "'697f2d856172cb8309d6b8b97dac4de344b549d4dee61edfb4962d8698b7fa803f4f93ff24393586e28b5b957ac3d1d369420ce53332712f997bd336d09ab02a'",
        )
        ex("escapeStringBash", "std.escapeStringBash(\"it's\")", "\"'it'\\\"'\\\"'s'\"")
        ex("escapeStringDollars", "std.escapeStringDollars('cost: \$5')", "'cost: \$\$5'")
        ex("escapeStringJson", "std.escapeStringJson('say \"hi\"')", "'\"say \\\\\"hi\\\\\"\"'")
        ex("escapeStringPython", "std.escapeStringPython('a\\nb')", "'\"a\\\\nb\"'")
        ex("escapeStringXML", "std.escapeStringXML('<a & b>')", "'&lt;a &amp; b&gt;'")

        // -- regular expressions (sjsonnet-only, see the note) --
        ex("regexQuoteMeta", "std.regexQuoteMeta('a.b')", "'a\\\\.b'", NOT_IN_GO_JSONNET + "`std.native('escapeStringRegex')`.")
        ex("regexFullMatch", "std.regexFullMatch('a(.)c', 'abc').captures", "['b']", NOT_IN_GO_JSONNET + "`std.native('regexMatch')`.")
        ex("regexPartialMatch", "std.regexPartialMatch('b+', 'abbc').string", "'bb'", NOT_IN_GO_JSONNET + "`std.native('regexMatch')`.")
        ex("regexReplace", "std.regexReplace('a1b22', '[0-9]+', '#')", "'a#b22'", NOT_IN_GO_JSONNET + "`std.native('regexSubst')`.")
        ex("regexGlobalReplace", "std.regexGlobalReplace('a1b22', '[0-9]+', '#')", "'a#b#'", NOT_IN_GO_JSONNET + "`std.native('regexSubst')`.")

        // -- arrays --
        ex("range", "std.range(1, 4)", "[1, 2, 3, 4]")
        ex("makeArray", "std.makeArray(3, function(i) i * i)", "[0, 1, 4]")
        ex("map", "std.map(function(x) x * 2, [1, 2, 3])", "[2, 4, 6]")
        ex("mapWithIndex", "std.mapWithIndex(function(i, x) '%d:%s' % [i, x], ['a', 'b'])", "['0:a', '1:b']")
        ex("filter", "std.filter(function(x) x > 1, [1, 2, 3])", "[2, 3]")
        ex("filterMap", "std.filterMap(function(x) x > 1, function(x) x * 10, [1, 2, 3])", "[20, 30]")
        ex("flatMap", "std.flatMap(function(x) [x, x], [1, 2])", "[1, 1, 2, 2]")
        ex("foldl", "std.foldl(function(acc, x) acc + x, [1, 2, 3], 0)", "6")
        ex("foldr", "std.foldr(function(x, acc) acc + x, ['a', 'b', 'c'], '')", "'cba'")
        ex("flattenArrays", "std.flattenArrays([[1, 2], [3]])", "[1, 2, 3]")
        ex("flattenDeepArray", "std.flattenDeepArray([1, [2, [3]]])", "[1, 2, 3]")
        ex("reverse", "std.reverse([1, 2, 3])", "[3, 2, 1]")
        ex("sort", "std.sort([{ n: 2 }, { n: 1 }], keyF=function(o) o.n)", "[{ n: 1 }, { n: 2 }]")
        ex("uniq", "std.uniq([1, 1, 2, 2, 3])", "[1, 2, 3]")
        ex("slice", "std.slice([1, 2, 3, 4, 5], 1, 4, 1)", "[2, 3, 4]")
        ex("count", "std.count([1, 2, 1, 1], 1)", "3")
        ex("find", "std.find('b', ['a', 'b', 'b'])", "[1, 2]")
        ex("member", "std.member([1, 2, 3], 2)", "true")
        ex("contains", "std.contains(['a', 'b'], 'b')", "true")
        ex("remove", "std.remove([1, 2, 3], 2)", "[1, 3]")
        ex("removeAt", "std.removeAt([1, 2, 3], 0)", "[2, 3]")
        ex("sum", "std.sum([1, 2, 3])", "6")
        ex("avg", "std.avg([1, 2, 3, 6])", "3")
        ex("minArray", "std.minArray([3, 1, 4])", "1")
        ex("maxArray", "std.maxArray([3, 1, 4])", "4")
        ex("all", "std.all([true, 1 < 2])", "true")
        ex("any", "std.any([false, 1 > 2])", "false")

        // -- sets (sorted arrays without duplicates) --
        ex("set", "std.set([3, 1, 3, 2])", "[1, 2, 3]")
        ex("setMember", "std.setMember(2, [1, 2, 3])", "true")
        ex("setUnion", "std.setUnion([1, 2], [2, 3])", "[1, 2, 3]")
        ex("setInter", "std.setInter([1, 2, 3], [2, 3, 4])", "[2, 3]")
        ex("setDiff", "std.setDiff([1, 2, 3], [2])", "[1, 3]")

        // -- objects --
        ex("get", "std.get({ a: 1 }, 'b', 0)", "0")
        ex("objectFields", "std.objectFields({ a: 1, b:: 2 })", "['a']")
        ex("objectFieldsAll", "std.objectFieldsAll({ a: 1, b:: 2 })", "['a', 'b']")
        ex("objectFieldsEx", "std.objectFieldsEx({ a: 1, b:: 2 }, true)", "['a', 'b']")
        ex("objectHas", "std.objectHas({ a:: 1 }, 'a')", "false")
        ex("objectHasAll", "std.objectHasAll({ a:: 1 }, 'a')", "true")
        ex("objectHasEx", "std.objectHasEx({ a:: 1 }, 'a', true)", "true")
        ex("objectValues", "std.objectValues({ a: 1, b: 2 })", "[1, 2]")
        ex("objectValuesAll", "std.objectValuesAll({ a: 1, b:: 2 })", "[1, 2]")
        ex("objectKeysValues", "std.objectKeysValues({ a: 1 })", "[{ key: 'a', value: 1 }]")
        ex("objectKeysValuesAll", "std.objectKeysValuesAll({ a:: 1 })", "[{ key: 'a', value: 1 }]")
        ex("objectRemoveKey", "std.objectRemoveKey({ a: 1, b: 2 }, 'a')", "{ b: 2 }")
        ex("mapWithKey", "std.mapWithKey(function(k, v) v * 2, { a: 1, b: 2 })", "{ a: 2, b: 4 }")
        ex("mergePatch", "std.mergePatch({ a: 1, b: 2 }, { b: null, c: 3 })", "{ a: 1, c: 3 }")
        ex("prune", "std.prune({ a: null, b: 1 })", "{ b: 1 }")

        // -- parsing and manifesting --
        ex("parseJson", "std.parseJson('{\"a\": [1, 2]}')", "{ a: [1, 2] }")
        ex("parseYaml", "std.parseYaml('a: 1')", "{ a: 1 }")
        ex("manifestJson", "std.manifestJson({ a: 1 })", "'{\\n    \"a\": 1\\n}'")
        ex("manifestJsonEx", "std.manifestJsonEx({ a: [1] }, '  ')", "'{\\n  \"a\": [\\n    1\\n  ]\\n}'")
        ex("manifestJsonMinified", "std.manifestJsonMinified({ a: [1, 2] })", "'{\"a\":[1,2]}'")
        ex("manifestYamlDoc", "std.manifestYamlDoc({ a: [1, 2] }, quote_keys=false)", "'a:\\n- 1\\n- 2'")
        ex(
            "manifestYamlStream", "std.manifestYamlStream([{ a: 1 }, { b: 2 }], quote_keys=false)",
            "'---\\na: 1\\n---\\nb: 2\\n...\\n'",
        )
        ex("manifestIni", "std.manifestIni({ main: { a: 1 }, sections: { s: { b: 'x' } } })", "'a = 1\\n[s]\\nb = x\\n'")
        ex("manifestPython", "std.manifestPython({ a: true })", "'{\"a\": True}'")
        ex("manifestPythonVars", "std.manifestPythonVars({ a: 1 })", "'a = 1\\n'")
        ex("manifestToml", "std.manifestToml({ a: 1 })", "'a = 1'")
        ex("manifestTomlEx", "std.manifestTomlEx({ a: 1, b: 'x' }, '  ')", "'a = 1\\nb = \"x\"'")
        ex("manifestXmlJsonml", "std.manifestXmlJsonml(['p', { class: 'x' }, 'hi'])", "'<p class=\"x\">hi</p>'")
    }
}
