package com.dz.intellijjsonnet.lang

import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetLocalReference
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
        val refs = PsiTreeUtil.collectElementsOfType(file, com.dz.intellijjsonnet.lang.psi.JsonnetNameRef::class.java)
        val usage = refs.first { it.text == "x" }
        val reference = usage.reference as JsonnetLocalReference
        val target = reference.resolve()
        assertNotNull("expected local `x` to resolve", target)
        assertEquals("x", (target as com.dz.intellijjsonnet.lang.psi.JsonnetBind).nameIdentifier?.text)
    }

    fun `test self field reference resolves`() {
        val file = createPsiFile("test", "{ a: 1, b: self.a }")
        ensureParsed(file)
        val dotSuffixes = PsiTreeUtil.collectElementsOfType(file, com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix::class.java)
        val suffix = dotSuffixes.single()
        val target = suffix.reference?.resolve()
        assertNotNull("expected self.a to resolve", target)
        assertEquals("a", (target as com.dz.intellijjsonnet.lang.psi.JsonnetField).let {
            com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver.fieldNameText(it)
        })
    }
}
