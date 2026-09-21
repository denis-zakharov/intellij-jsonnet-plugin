package com.dz.intellijjsonnet.platform

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Hover goes through the platform's documentation pipeline (a `DocumentationTarget` from the caret, then
 * the legacy `lang.documentationProvider` behind it), so it is tested there rather than by calling
 * the provider directly.
 */
class StdLibHoverTest : BasePlatformTestCase() {

    /** The hover as plain text: the snippet is syntax-highlighted, so its HTML is full of spans. */
    private fun hoverAt(text: String): String? {
        myFixture.configureByText("a.jsonnet", text)
        val targets = IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
        val html = targets.firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html } ?: return null
        // The highlighter emits spaces as `&#32;`; `unescapeXmlEntities` only knows the named ones.
        val withoutTags = html.replace(Regex("<[^>]+>"), "")
        return StringUtil.unescapeXmlEntities(withoutTags.replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() })
    }

    fun `test hover on a std call shows its signature and a usage snippet with the result`() {
        val doc = hoverAt("std.jo<caret>in(',', [])")!!
        assertTrue(doc, doc.contains("std.join(sep, arr)"))
        assertTrue(doc, doc.contains("std.join(', ', ['a', 'b', 'c'])"))
        assertTrue(doc, doc.contains("// => 'a, b, c'"))
    }

    fun `test hover includes the official description, its version and credit`() {
        val doc = hoverAt("std.jo<caret>in(',', [])")!!
        assertTrue(doc, doc.contains("concatenated with sep used as a delimiter"))
        assertTrue(doc, doc.contains("Since:Jsonnet 0.10.0"))
        assertTrue(doc, doc.contains("Jsonnet standard library reference (CC BY 2.5)"))
    }

    fun `test description comes before the example`() {
        val doc = hoverAt("std.jo<caret>in(',', [])")!!
        assertTrue(doc, doc.indexOf("std.join(sep, arr)") < doc.indexOf("delimiter"))
        assertTrue(doc, doc.indexOf("delimiter") < doc.indexOf("// => 'a, b, c'"))
    }

    fun `test a function the reference doesn't describe still gets signature and example but no credit`() {
        val doc = hoverAt("std.a<caret>bs(1)")!!
        assertTrue(doc, doc.contains("std.abs(n)"))
        assertTrue(doc, doc.contains("// => 3"))
        assertFalse(doc, doc.contains("CC BY"))
    }

    fun `test multi-line blocks from the reference survive`() {
        val doc = hoverAt("std.escapeStringX<caret>ML('')")!!
        // Rendered text: the block documents the escapes, so it contains the literal `&lt;`.
        assertTrue(doc, doc.contains("\"<\": \"&lt;\""))
    }

    fun `test optional parameters are bracketed`() {
        val doc = hoverAt("std.so<caret>rt([])")!!
        assertTrue(doc, doc.contains("std.sort(arr, [keyF])"))
    }

    fun `test hover on a std value has no parameter list`() {
        val doc = hoverAt("std.p<caret>i")!!
        assertTrue(doc, doc.startsWith("std.pi"))
        assertFalse(doc, doc.contains("std.pi("))
        assertTrue(doc, doc.contains("// => 3.141592653589793"))
    }

    fun `test an example that depends on the environment has no result line`() {
        val doc = hoverAt("std.extV<caret>ar('x')")!!
        assertTrue(doc, doc.contains("std.extVar(x)"))
        assertFalse(doc, doc.contains("// =>"))
    }

    fun `test sjsonnet-only functions warn that tk lacks them`() {
        val doc = hoverAt("std.regexRepl<caret>ace('a', 'a', 'b')")!!
        assertTrue(doc, doc.contains("fails under tk"))
        assertTrue(doc, doc.contains("std.native('regexSubst')"))
    }

    fun `test hover on a native function name is unchanged`() {
        val doc = hoverAt("std.native('sha<caret>256')")!!
        assertTrue(doc, doc.contains("std.native('sha256')"))
        assertTrue(doc, doc.contains("SHA-256"))
    }

    fun `test a member of something other than std gets no std documentation`() {
        val doc = hoverAt("local o = { join(a):: a }; o.jo<caret>in(1)")
        assertFalse(doc.toString(), doc.orEmpty().contains("std.join"))
    }
}
