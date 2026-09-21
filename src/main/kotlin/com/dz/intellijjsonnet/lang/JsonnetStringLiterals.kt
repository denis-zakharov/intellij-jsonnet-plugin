package com.dz.intellijjsonnet.lang

/**
 * The value of a Jsonnet string token (`STRING` covers quoted, verbatim `@'..'` and `|||` text-block
 * literals), or `null` when the text isn't a well-formed literal — callers treat that as "not statically known".
 */
object JsonnetStringLiterals {

    fun decode(token: String): String? = when {
        token.startsWith("|||") -> textBlock(token)
        token.startsWith("@") -> verbatim(token)
        token.length >= 2 && (token[0] == '"' || token[0] == '\'') && token.last() == token[0] ->
            escaped(token.substring(1, token.length - 1))
        else -> null
    }

    private fun escaped(body: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i++]
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (i >= body.length) return null
            when (val e = body[i++]) {
                '"', '\'', '\\', '/' -> out.append(e)
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val hex = body.substring(i, minOf(i + 4, body.length))
                    out.append((hex.takeIf { it.length == 4 }?.toIntOrNull(16) ?: return null).toChar())
                    i += 4
                }
                else -> return null
            }
        }
        return out.toString()
    }

    /** `@'it''s'` — the only escape is doubling the quote character. */
    private fun verbatim(token: String): String? {
        if (token.length < 3) return null
        val quote = token[1]
        if ((quote != '"' && quote != '\'') || token.last() != quote) return null
        return token.substring(2, token.length - 1).replace("$quote$quote", quote.toString())
    }

    /**
     * `|||`, newline, lines indented by at least the first line's indentation, newline, `|||`. The indentation
     * is removed and the result ends with a newline, as in the language spec.
     */
    private fun textBlock(token: String): String? {
        val text = token.replace("\r\n", "\n")
        val firstNewline = text.indexOf('\n')
        val lastNewline = text.lastIndexOf('\n')
        if (firstNewline < 0 || lastNewline <= firstNewline) return if (firstNewline >= 0) "" else null
        val lines = text.substring(firstNewline + 1, lastNewline).split('\n')
        val indent = lines.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' } ?: return ""
        return lines.joinToString("") { line -> (if (line.startsWith(indent)) line.substring(indent.length) else line.trimStart()) + "\n" }
    }
}
