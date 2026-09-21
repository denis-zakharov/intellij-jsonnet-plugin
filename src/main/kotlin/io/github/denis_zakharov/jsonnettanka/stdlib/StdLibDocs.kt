package io.github.denis_zakharov.jsonnettanka.stdlib

/**
 * Descriptions from the official Jsonnet standard library reference (https://jsonnet.org/ref/stdlib.html),
 * bundled as `stdlib/jsonnet-stdlib-docs.txt`, which `scripts/update-stdlib-docs.py` generates from the page.
 * The page is CC BY 2.5; the hover credits it and `THIRD_PARTY_NOTICES.md` records the terms.
 *
 * The reference only describes some functions (the math and type-predicate sections are bare lists), so
 * [forName] is `null` for the rest. That is left as is rather than filled in with text of our own.
 */
object StdLibDocs {

    /** [html] is a fragment using only `p`, `em`, `code`, `a`, `ul`, `li` and `pre`; [anchor] is the page's own id for it. */
    data class Entry(val since: String?, val anchor: String, val html: String) {
        val url: String get() = "$PAGE#$anchor"
    }

    const val PAGE = "https://jsonnet.org/ref/stdlib.html"
    private const val RESOURCE = "/stdlib/jsonnet-stdlib-docs.txt"

    fun forName(name: String): Entry? = entries[name]

    internal val entries: Map<String, Entry> by lazy {
        val text = StdLibDocs::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (text == null) emptyMap() else parse(text)
    }

    /** Lines before the first `== ` are comments; then `== name | since | anchor` heads an entry whose body follows. */
    internal fun parse(text: String): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        var header: List<String>? = null
        val body = StringBuilder()

        fun flush() {
            val fields = header ?: return
            result[fields[0]] = Entry(fields[1].ifEmpty { null }, fields[2], body.toString().trim())
            body.setLength(0)
        }

        for (line in text.lineSequence()) {
            if (line.startsWith("== ")) {
                flush()
                header = line.removePrefix("== ").split(" | ").map { it.trim() }
                require(header!!.size == 3) { "malformed stdlib docs header: $line" }
            } else if (header != null) {
                body.append(line).append('\n')
            }
        }
        flush()
        return result
    }
}
