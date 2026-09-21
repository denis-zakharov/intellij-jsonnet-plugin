package io.github.denis_zakharov.jsonnettanka.stdlib

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StdLibDocsTest {

    @Test
    fun `the bundled reference text is actually loaded`() {
        // A missing or misnamed resource degrades to "no descriptions" instead of failing, so pin it.
        assertTrue(StdLibDocs.entries.size >= 100, "only ${StdLibDocs.entries.size} entries loaded")
        assertTrue("delimiter" in StdLibDocs.forName("join")!!.html)
    }

    @Test
    fun `every described name is a real std member`() {
        val stale = StdLibDocs.entries.keys - StdLibRegistry.memberNames.toSet()
        assertEquals(emptySet<String>(), stale, "descriptions for functions sjsonnet doesn't have (or a misspelt alias)")
    }

    @Test
    fun `descriptions only use the tags the hover is meant to render`() {
        val allowed = setOf("p", "em", "code", "a", "ul", "li", "pre")
        for ((name, entry) in StdLibDocs.entries) {
            val tags = Regex("</?([a-zA-Z0-9]+)").findAll(entry.html).map { it.groupValues[1] }.toSet()
            assertTrue(allowed.containsAll(tags), "std.$name uses ${tags - allowed}")
            for (href in Regex("href=\"([^\"]*)\"").findAll(entry.html).map { it.groupValues[1] }) {
                assertTrue(href.startsWith("https://") || href.startsWith("http://"), "std.$name links to $href")
            }
        }
    }

    @Test
    fun `versions look like versions and the anchor points into the reference`() {
        for ((name, entry) in StdLibDocs.entries) {
            entry.since?.let { assertTrue(Regex("\\d+\\.\\d+\\.\\d+").matches(it), "std.$name since '$it'") }
            assertTrue(entry.anchor.startsWith("std-"), "std.$name anchor '${entry.anchor}'")
        }
        // The page spells this one differently from the function, so the link must use the page's own id.
        assertEquals("https://jsonnet.org/ref/stdlib.html#std-escapeStringXml", StdLibDocs.forName("escapeStringXML")!!.url)
    }

    @Test
    fun `one-line examples are omitted but longer explanations stay`() {
        assertTrue("Example:" !in StdLibDocs.forName("join")!!.html)
        assertTrue("<pre>" in StdLibDocs.forName("manifestYamlDoc")!!.html)
    }

    @Test
    fun `the deprecation warning on base64Decode is kept`() {
        assertTrue("Deprecated" in StdLibDocs.forName("base64Decode")!!.html)
    }

    @Test
    fun `functions the reference doesn't describe have no entry`() {
        assertNull(StdLibDocs.forName("abs"))
        assertNull(StdLibDocs.forName("regexReplace"))
    }

    @Test
    fun `parse reads headers, multi-line bodies and skips leading comments`() {
        val parsed = StdLibDocs.parse("# comment\n== a | 0.1.0 | std-a\n<p>x</p>\n<pre>1\n2</pre>\n== b |  | std-b\n<p>y</p>\n")
        assertEquals(setOf("a", "b"), parsed.keys)
        assertEquals("0.1.0", parsed["a"]!!.since)
        assertEquals("<p>x</p>\n<pre>1\n2</pre>", parsed["a"]!!.html)
        assertNull(parsed["b"]!!.since)
    }

    @Test
    fun `the generated file credits the source and license`() {
        val text = StdLibDocs::class.java.getResourceAsStream("/stdlib/jsonnet-stdlib-docs.txt")!!.readBytes().toString(Charsets.UTF_8)
        assertTrue("CC BY 2.5" in text && "https://jsonnet.org/ref/stdlib.html" in text)
    }
}
