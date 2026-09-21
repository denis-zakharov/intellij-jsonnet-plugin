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
    // entirely until a TODO.md item-2 scale test against a real
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
}
