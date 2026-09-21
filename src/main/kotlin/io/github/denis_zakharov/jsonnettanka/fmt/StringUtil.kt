/*
Copyright 2019 Google Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Ported to Kotlin from go-jsonnet v0.22.0 (internal/parser/string_util.go, commit 567b61a); modified.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/** Compiles out the escape codes in the body of a `"..."` / `'...'` literal. Throws [IllegalArgumentException]. */
internal fun unescapeJsonnetString(s: String): String {
    val buf = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        i++
        if (c != '\\') {
            buf.append(c)
            continue
        }
        if (i >= s.length) throw IllegalArgumentException("Truncated escape sequence in string literal.")
        val c2 = s[i]
        i++
        when (c2) {
            '"' -> buf.append('"')
            '\'' -> buf.append('\'')
            '\\' -> buf.append('\\')
            '/' -> buf.append('/') // See json.org, \/ is a valid escape.
            'b' -> buf.append('\b')
            'f' -> buf.append('\u000c')
            'n' -> buf.append('\n')
            'r' -> buf.append('\r')
            't' -> buf.append('\t')
            'u' -> {
                if (i + 4 > s.length) throw IllegalArgumentException("Truncated unicode escape sequence in string literal.")
                var code = parseHex4(s, i)
                    ?: throw IllegalArgumentException("Unicode escape sequence was malformed: ${s.take(4)}")
                i += 4
                if (code in 0xD800..0xDFFF) {
                    val high = code
                    if (i + 6 > s.length) {
                        throw IllegalArgumentException("Truncated unicode surrogate pair escape sequence in string literal.")
                    }
                    if (s.substring(i, i + 2) != "\\u") {
                        throw IllegalArgumentException("Unicode surrogate pair escape sequence missing low surrogate in string literal.")
                    }
                    i += 2
                    val low = parseHex4(s, i)
                        ?: throw IllegalArgumentException("Unicode low surrogate escape sequence was malformed: ${s.take(4)}")
                    i += 4
                    // Go's utf16.DecodeRune yields U+FFFD for anything that isn't a valid pair.
                    code = if (high in 0xD800..0xDBFF && low in 0xDC00..0xDFFF) {
                        Character.toCodePoint(high.toChar(), low.toChar())
                    } else {
                        0xFFFD
                    }
                }
                buf.appendCodePoint(code)
            }
            else -> throw IllegalArgumentException("Unknown escape sequence in string literal: \\$c2")
        }
    }
    return buf.toString()
}

private fun parseHex4(s: String, from: Int): Int? {
    var v = 0
    for (k in 0 until 4) {
        val d = Character.digit(s[from + k], 16)
        if (d < 0) return null
        v = v * 16 + d
    }
    return v
}

/** The inverse of [unescapeJsonnetString]. */
internal fun escapeJsonnetString(s: String, single: Boolean): String {
    val buf = StringBuilder()
    var i = 0
    while (i < s.length) {
        val r = s.codePointAt(i)
        i += Character.charCount(r)
        when (r) {
            '"'.code -> {
                if (!single) buf.append('\\')
                buf.append('"')
            }
            '\''.code -> {
                if (single) buf.append('\\')
                buf.append('\'')
            }
            '\\'.code -> buf.append("\\\\")
            0x08 -> buf.append("\\b")
            0x0c -> buf.append("\\f")
            '\n'.code -> buf.append("\\n")
            '\r'.code -> buf.append("\\r")
            '\t'.code -> buf.append("\\t")
            0 -> buf.append("\\u0000")
            else -> {
                if (r < 0x20 || (r in 0x7f..0x9f)) {
                    buf.append("\\u").append("%04x".format(r))
                } else {
                    buf.appendCodePoint(r)
                }
            }
        }
    }
    return buf.toString()
}
