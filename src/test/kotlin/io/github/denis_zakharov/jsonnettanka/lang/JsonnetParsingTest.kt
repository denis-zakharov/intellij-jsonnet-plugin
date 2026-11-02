package io.github.denis_zakharov.jsonnettanka.lang

import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetLocalReference
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

/**
 * Real grammar/PSI test via IntelliJ's lightweight [ParsingTestCase] (no full
 * IDE sandbox needed, unlike BasePlatformTestCase — that heavier framework is
 * Phase 4's job per the plan). Confirms the Phase 1 grammar actually parses a
 * representative file without PSI errors, and that reference resolution wires
 * up end-to-end.
 */
class JsonnetParsingTest : ParsingTestCase("", "jsonnet", JsonnetParserDefinition()) {

    override fun getTestDataPath(): String = "."
    override fun skipSpaces(): Boolean = true

    private fun assertNoErrors(text: String) {
        val file = createPsiFile("test", text)
        ensureParsed(file)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        if (errors.isNotEmpty()) {
            val details = errors.joinToString("\n") { "  ${it.errorDescription} at offset ${it.textOffset}: '${it.text}'" }
            throw AssertionError("Unexpected parse errors in:\n$text\n$details")
        }
    }

    fun `test kitchen sink parses without errors`() {
        assertNoErrors(
            """
            // comment
            local greeting = 'hello',
                  add(x, y=1) = x + y,
                  nums = [x * 2 for x in [1, 2, 3] if x > 1];

            local Base = {
              name:: 'base',
              greet(): 'hi ' + self.name,
            };

            Base + {
              local secret = 'shh',
              name: greeting,
              tags: ['a', 'b', std.extVar('env')],
              nested: {
                [secret]: true,
                deep+: { a: 1, b: 2 },
              },
              fn: function(a, b=self.name) a + b,
              cond: if add(1, 2) > 2 then 'big' else 'small',
              text: |||
                multi
                line
              |||,
              verbatim: @'it''s here',
              slice: nums[1:3],
              root: ${'$'}.tags,
              err: if false then error 'nope' else null,
              imported: import 'other.libsonnet',
              raw: importstr 'raw.txt',
              assert std.isNumber(add(1, 2)) : 'must be a number',
            }
            """.trimIndent(),
        )
    }

    fun `test object assert member`() {
        assertNoErrors(
            """
            {
              assert std.isNumber(1) : 'must be a number',
            }
            """.trimIndent(),
        )
    }

    fun `test function default value referencing self`() {
        assertNoErrors("{ name: 'x', fn: function(a, b=self.name) a + b }")
    }

    fun `test array slice`() {
        assertNoErrors("local nums = [1,2,3]; nums[1:3]")
    }

    // Real Jsonnet spec grammar: `expr { ... }` (an object literal directly
    // juxtaposed after any expression, no operator) is sugar for
    // `expr + { ... }` — idiomatic and extremely common in real Tanka/
    // k8s-libsonnet code (e.g. `deployment.new() { spec+: {...} } `). Missing
    // entirely until the ADR 0002 scale test against a real
    // jsonnet-libs/k8s-libsonnet checkout (~690 files) found exactly one
    // parse failure, on exactly this construct.
    fun `test object literal juxtaposed after a call is a mixin apply`() {
        assertNoErrors("local patch = {}; local cronPatch = patch { mapContainers(f):: { x: f } }; cronPatch")
    }

    fun `test object literal juxtaposed after a bare name`() {
        assertNoErrors("local base = { a: 1 }; base { b: 2 }")
    }

    fun `test two object literals juxtaposed back to back`() {
        assertNoErrors("{ a: 1 } { b: 2 }")
    }

    fun `test digit separators parse without errors`() {
        assertNoErrors("{ a: 1_000, b: 1_0.5_0e1_0 }")
    }

    fun `test dollar field reference`() {
        assertNoErrors("{ tags: [1], root: \$.tags }")
    }

    fun `test text block field followed by comma`() {
        assertNoErrors(
            "{ text: |||\n  multi\n  line\n|||,\n}",
        )
    }

    fun `test local variable reference resolves`() {
        val file = createPsiFile("test", "local x = 1; x + x")
        ensureParsed(file)
        val refs = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef::class.java)
        val usage = refs.first { it.text == "x" }
        val reference = usage.reference as JsonnetLocalReference
        val target = reference.resolve()
        assertNotNull("expected local `x` to resolve", target)
        assertEquals("x", (target as io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind).nameIdentifier?.text)
    }

    fun `test std native name argument is detected`() {
        val file = createPsiFile("test", "std.native('parseYaml')(x)")
        ensureParsed(file)
        val strings = PsiTreeUtil.collectElementsOfType(file, com.intellij.psi.PsiElement::class.java)
            .filter { it.node?.elementType == io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes.STRING }
        val stringLiteral = strings.single()
        assertTrue(io.github.denis_zakharov.jsonnettanka.tanka.TankaNativeFunctions.isNativeNameArgument(stringLiteral))
    }

    fun `test non-native string argument is not detected`() {
        val file = createPsiFile("test", "std.foo('parseYaml')")
        ensureParsed(file)
        val strings = PsiTreeUtil.collectElementsOfType(file, com.intellij.psi.PsiElement::class.java)
            .filter { it.node?.elementType == io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes.STRING }
        val stringLiteral = strings.single()
        assertFalse(io.github.denis_zakharov.jsonnettanka.tanka.TankaNativeFunctions.isNativeNameArgument(stringLiteral))
    }

    fun `test tk access chain is detected`() {
        val file = createPsiFile("test", "local tk = import 'tk'; tk.env.spec.namespace")
        ensureParsed(file)
        val dotSuffixes = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix::class.java)
            .sortedBy { it.textOffset }
        // .env  .spec  .namespace
        assertEquals(3, dotSuffixes.size)
        assertEquals(emptyList<String>(), io.github.denis_zakharov.jsonnettanka.tanka.TankaTkModule.tkAccessChainBefore(dotSuffixes[0]))
        assertEquals(listOf("env"), io.github.denis_zakharov.jsonnettanka.tanka.TankaTkModule.tkAccessChainBefore(dotSuffixes[1]))
        assertEquals(listOf("env", "spec"), io.github.denis_zakharov.jsonnettanka.tanka.TankaTkModule.tkAccessChainBefore(dotSuffixes[2]))
    }

    fun `test non-tk local does not produce a tk access chain`() {
        val file = createPsiFile("test", "local other = { env: {} }; other.env.spec")
        ensureParsed(file)
        val dotSuffixes = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix::class.java)
        assertTrue(dotSuffixes.all { io.github.denis_zakharov.jsonnettanka.tanka.TankaTkModule.tkAccessChainBefore(it) == null })
    }

    fun `test PsiNameIdentifierOwner read side for bind, param, forSpec, field`() {
        // The write side (setName/handleElementRename, both go through
        // PsiFileFactory to mint a replacement identifier leaf) needs a real
        // PsiFileFactory service that ParsingTestCase's minimal MockApplication
        // doesn't register — reviewed but not automated-tested here, same
        // ParsingTestCase-vs-BasePlatformTestCase tradeoff noted in Phase 2/3
        // (BasePlatformTestCase itself hangs in this environment). This covers
        // the read side, which needs nothing beyond parsing.
        val file = createPsiFile(
            "test",
            "local x(p) = [p for p in [1]]; { f(p): p, x: x(1) }",
        )
        ensureParsed(file)

        val bind = PsiTreeUtil.findChildOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind::class.java)!!
        assertEquals("x", (bind as com.intellij.psi.PsiNameIdentifierOwner).name)

        val param = PsiTreeUtil.findChildOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetParam::class.java)!!
        assertEquals("p", (param as com.intellij.psi.PsiNameIdentifierOwner).name)

        val forSpec = PsiTreeUtil.findChildOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec::class.java)!!
        assertEquals("p", (forSpec as com.intellij.psi.PsiNameIdentifierOwner).name)

        val field = PsiTreeUtil.findChildrenOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField::class.java)
            .first { it.text.startsWith("f(") }
        assertEquals("f", (field as com.intellij.psi.PsiNameIdentifierOwner).name)
    }

    fun `test self field reference resolves`() {
        val file = createPsiFile("test", "{ a: 1, b: self.a }")
        ensureParsed(file)
        val dotSuffixes = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix::class.java)
        val suffix = dotSuffixes.single()
        val target = suffix.reference?.resolve()
        assertNotNull("expected self.a to resolve", target)
        assertEquals("a", (target as io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField).let {
            io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver.fieldNameText(it)
        })
    }

    // ---- Comprehensions: the examples from https://jsonnet.org/learning/tutorial.html ----

    fun `test tutorial array comprehensions parse`() {
        assertNoErrors(
            """
            local arr = std.range(5, 8);
            {
              array_comprehensions: {
                higher: [x + 1 for x in arr],
                lower: [x - 1 for x in arr],
                evens: [x for x in arr if x % 2 == 0],
                odds: [x for x in arr if x % 2 == 1],
                evens_and_odds: [
                  "%d-%d" % [x, y]
                  for x in arr
                  if x % 2 == 0
                  for y in arr
                  if y % 2 == 1
                ],
              },
            }
            """.trimIndent(),
        )
    }

    fun `test tutorial object comprehensions parse`() {
        assertNoErrors(
            """
            local arr = std.range(5, 8);
            {
              object_comprehensions: {
                ["foo" + x]: true for x in [1, 2, 3]
              },
              mixed: { [x + "-" + y]: x + y for x in ["a", "b"] for y in ["c", "d"] if x != y },
              with_local: { local y = x * 2, ["k" + x]: y for x in arr },
              local_after_local: { local a = 1, local b = 2, [x]: a + b for x in ["p"] },
              keyed: { [x]: x for x in arr if x > 5 },
              trailing_comma_free: [x for x in arr],
            }
            """.trimIndent(),
        )
    }

    fun `test comprehension psi shapes`() {
        val file = createPsiFile(
            "test",
            "{ a: [x for x in [1] if x > 0], b: { [y]: y for y in ['q'] if y != 'z' for z in [2] } }",
        )
        ensureParsed(file)
        val arr = PsiTreeUtil.findChildOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetArrayComprehension::class.java)!!
        assertEquals(1, arr.forSpecList.size)
        assertEquals(1, PsiTreeUtil.findChildrenOfType(arr, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetIfSpec::class.java).size)
        val obj = PsiTreeUtil.findChildOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetObjectComprehension::class.java)!!
        assertEquals(2, obj.forSpecList.size)
    }

    fun `test comprehension variables resolve to their for spec`() {
        val file = createPsiFile(
            "test",
            "[x + y for x in [1] if x > 0 for y in [x]]",
        )
        ensureParsed(file)
        val refs = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef::class.java)
        for (r in refs.filter { it.text == "x" || it.text == "y" }) {
            val target = r.reference?.resolve()
            assertTrue("`${r.text}` at ${r.textOffset} should resolve to a for-spec, got $target",
                target is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec)
            assertEquals(r.text, (target as io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec).nameIdentifier?.text)
        }
    }

    fun `test object comprehension variables and locals resolve`() {
        val file = createPsiFile("test", "{ local y = x * 2, ['k' + x]: y for x in [1] }")
        ensureParsed(file)
        val refs = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef::class.java)
        val xs = refs.filter { it.text == "x" }
        assertEquals(2, xs.size)
        xs.forEach { assertTrue(it.reference?.resolve() is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec) }
        assertTrue(refs.single { it.text == "y" }.reference?.resolve() is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind)
    }

    // ---- Computed field names, alone and combined with comprehensions ----

    fun `test computed field names with every field operator parse`() {
        assertNoErrors(
            """
            local k = 'a';
            {
              [k]: 1,
              [k + 'b']:: 2,
              [k + 'c']::: 3,
              [k + 'd']+: { x: 1 },
              [k + 'e']+:: { x: 1 },
              [k + 'f'](p, q=1): p + q,
              ['g' + std.toString(1)]: [x for x in [1, 2]],
              [if true then 'h' else 'i']: { [y]: y for y in ['j'] },
              [std.join('-', [k, k])]: { local z = 1, [w]: z for w in ['m'] },
            }
            """.trimIndent(),
        )
    }

    fun `test comprehensions nested in computed keys and values parse`() {
        assertNoErrors("{ [x]: { [y]: [x, y] for y in ['q'] } for x in ['p'] }")
        assertNoErrors("{ [std.join('', [c for c in ['a']])]: 1 }")
        assertNoErrors("{ [{ [k]: 1 for k in ['a'] }.a + 'x']: 2 }")
    }

    fun `test names inside computed keys resolve`() {
        val file = createPsiFile("test", "{ [k + w]: v for k in ['a'] for w in [k] }")
        ensureParsed(file)
        val refs = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef::class.java)
        for (r in refs.filter { it.text == "k" || it.text == "w" }) {
            assertTrue("`${r.text}` should resolve to a for-spec",
                r.reference?.resolve() is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec)
        }
        // `v` is deliberately undefined
        assertNull(refs.single { it.text == "v" }.reference?.resolve())
    }

    fun `test computed key is not a plain field name`() {
        val file = createPsiFile("test", "{ [k]: 1, a: 2 }")
        ensureParsed(file)
        val fields = PsiTreeUtil.findChildrenOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField::class.java).toList()
        assertEquals(2, fields.size)
        assertNull(io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver.fieldNameText(fields[0]))
        assertEquals("a", io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver.fieldNameText(fields[1]))
    }

    fun `test object comprehension with the merge operator parses`() {
        assertNoErrors("{ [key]+: { f1: 'v1' } for key in ['one', 'two'] }")
        assertNoErrors("{ a: { x: 1 } } + { [key]+: { f1: 'v1' } for key in ['a', 'b'] }")
        assertNoErrors("{ local v = 1, [key]+: { f1: v } for key in ['one'] if key != 'two' }")
    }

    fun `test object locals are not visible in computed keys but are in values`() {
        val file = createPsiFile("test", "{ local y = 'a', [y]: y }")
        ensureParsed(file)
        val ys = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef::class.java).filter { it.text == "y" }
        assertEquals(2, ys.size)
        assertNull("key `y` is outside the object's scope", ys[0].reference?.resolve())
        assertTrue(ys[1].reference?.resolve() is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind)
    }

    fun `test object comprehension locals are not visible in the key but loop variables are`() {
        val file = createPsiFile("test", "{ local y = 'a', [x + y]: y for x in ['q'] }")
        ensureParsed(file)
        val refs = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef::class.java)
        assertTrue(refs.single { it.text == "x" }.reference?.resolve() is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec)
        val ys = refs.filter { it.text == "y" }
        assertNull(ys[0].reference?.resolve())
        assertTrue(ys[1].reference?.resolve() is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind)
    }

    fun `test field parameters are not visible in its own computed key`() {
        val file = createPsiFile("test", "{ [p](p):: p }")
        ensureParsed(file)
        val ps = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef::class.java).filter { it.text == "p" }
        assertNull(ps[0].reference?.resolve())
        assertTrue(ps[1].reference?.resolve() is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetParam)
    }

    fun `test locals of an enclosing object stay visible in a nested object's computed key`() {
        val file = createPsiFile("test", "{ local y = 'a', inner: { [y]: 1 } }")
        ensureParsed(file)
        val y = PsiTreeUtil.collectElementsOfType(file, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef::class.java).single { it.text == "y" }
        assertTrue(y.reference?.resolve() is io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind)
    }

    fun `test self in a computed key skips the object it names a field of`() {
        val resolver = io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver
        val top = createPsiFile("test", "{ a: 1, [self.a]: 2 }")
        ensureParsed(top)
        val selfInTop = PsiTreeUtil.findChildOfType(top, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetComputedFieldName::class.java)!!
        assertNull(resolver.enclosingObjectLiteral(selfInTop.node.findChildByType(io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes.LBRACK)!!.treeNext.psi))
        // nested: `self` in the inner object's key is the outer object
        val nested = createPsiFile("test2", "{ a: 1, inner: { [self.a]: 2 } }")
        ensureParsed(nested)
        val key = PsiTreeUtil.findChildOfType(nested, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetComputedFieldName::class.java)!!
        val outer = PsiTreeUtil.findChildOfType(nested, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetObjectLiteral::class.java)!!
        assertSame(outer, resolver.enclosingObjectLiteral(key.firstChild))
        // and in a value, self is the object itself
        val value = createPsiFile("test3", "{ a: self.a }")
        ensureParsed(value)
        val lit = PsiTreeUtil.findChildOfType(value, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetObjectLiteral::class.java)!!
        val dot = PsiTreeUtil.findChildOfType(value, io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix::class.java)!!
        assertSame(lit, resolver.enclosingObjectLiteral(dot))
    }
}
