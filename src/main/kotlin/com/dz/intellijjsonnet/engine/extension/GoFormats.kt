package com.dz.intellijjsonnet.engine.extension

import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.DumperOptions
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.LoaderOptions
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.Yaml
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.constructor.SafeConstructor
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.nodes.Tag
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.representer.Representer
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.resolver.Resolver
import com.dz.intellijjsonnet.shaded.ujson.Arr
import com.dz.intellijjsonnet.shaded.ujson.Bool
import com.dz.intellijjsonnet.shaded.ujson.`Null$`
import com.dz.intellijjsonnet.shaded.ujson.Num
import com.dz.intellijjsonnet.shaded.ujson.Obj
import com.dz.intellijjsonnet.shaded.ujson.Readable
import com.dz.intellijjsonnet.shaded.ujson.Str
import com.dz.intellijjsonnet.shaded.ujson.Value
import com.dz.intellijjsonnet.shaded.ujson.`package` as UJson
import com.google.re2j.Matcher
import java.math.BigDecimal
import java.math.BigInteger
import java.io.StringReader
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.regex.Pattern

/**
 * Byte-for-byte reimplementations of the Go standard-library / `yaml.v3` behaviors Tanka's native
 * functions expose (`json.Indent`, `yaml.Marshal`, `regexp.QuoteMeta`, `Regexp.ReplaceAllString`).
 * Each was checked against the output of a real `tk eval`; `TankaNativesTest` pins those outputs.
 */
internal object GoFormats {

    /** `json.Indent(dst, src, "", strings.Repeat(" ", indent))`: re-indents the *text*, so key order and number spelling survive. */
    fun jsonIndent(json: String, indent: Int): String {
        parseJson(json) // Go validates first; ujson throws on the same malformed inputs
        val unit = " ".repeat(indent.coerceAtLeast(0))
        val out = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        var pendingOpen = false // just wrote { or [ ; only indent if it turns out non-empty
        fun newline() { out.append('\n'); repeat(depth) { out.append(unit) } }
        for (c in json) {
            if (inString) {
                out.append(c)
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue
            if (pendingOpen) {
                pendingOpen = false
                if (c == '}' || c == ']') { out.append(c); continue }
                depth++; newline()
            }
            when (c) {
                '"' -> { inString = true; out.append(c) }
                '{', '[' -> { out.append(c); pendingOpen = true }
                '}', ']' -> { depth--; newline(); out.append(c) }
                ',' -> { out.append(c); newline() }
                ':' -> out.append(": ")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    fun parseJson(json: String): Value = UJson.read(Readable.fromCharSequence(json), false)

    /** `regexp.QuoteMeta`: backslash-escapes exactly `\.+*?()|[]{}^$`. */
    fun quoteMeta(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            if (c in "\\.+*?()|[]{}^$") out.append('\\')
            out.append(c)
        }
        return out.toString()
    }

    /**
     * `Regexp.ReplaceAllString`'s template expansion: `$1`, `${1}`, `$name`, `${name}` and `$$`; a group
     * that doesn't exist expands to "" and, unlike java.util.regex, `\` is an ordinary character.
     */
    fun expandTemplate(template: String, m: Matcher, namedGroups: Map<String, Int>): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '$' || i + 1 >= template.length) { out.append(c); i++; continue }
            if (template[i + 1] == '$') { out.append('$'); i += 2; continue }
            val braced = template[i + 1] == '{'
            val start = if (braced) i + 2 else i + 1
            var end = start
            while (end < template.length && (template[end].isLetterOrDigit() || template[end] == '_') && template[end].code < 128) end++
            if (end == start || (braced && (end >= template.length || template[end] != '}'))) {
                out.append(c); i++; continue // not a valid reference: Go leaves the '$' as-is
            }
            val name = template.substring(start, end)
            val group = name.toIntOrNull()
            val text = when {
                group != null -> if (group <= m.groupCount()) m.group(group) else null
                else -> namedGroups[name]?.let { m.group(it) }
            }
            out.append(text ?: "")
            i = if (braced) end + 1 else end
        }
        return out.toString()
    }

    /** `yaml.Marshal` (gopkg.in/yaml.v3, indent 4) of data decoded from JSON. Always ends with a newline. */
    fun yamlMarshal(json: String): String {
        val out = StringBuilder()
        when (val root = parseJson(json)) {
            is Obj -> if (isEmpty(root)) out.append("{}\n") else emitMap(root, out, keyCol = 0, base = 0, firstInline = false)
            is Arr -> if (isEmpty(root)) out.append("[]\n") else emitSeq(root, out, seqIndent = 0, firstInline = false)
            else -> out.append(scalar(root)).append('\n')
        }
        return out.toString()
    }

    /**
     * `yaml.Decoder` over every document in [text], returned as a JSON array text. yaml.v3 resolves plain
     * scalars per YAML 1.2 (`yes`/`on` stay strings — SnakeYAML's default is 1.1, and turns them into
     * booleans) and turns timestamps into RFC 3339 strings when marshalled to JSON.
     */
    fun yamlDocumentsAsJson(text: String): String {
        val loader = LoaderOptions()
        val dumper = DumperOptions()
        val yaml = Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader, Yaml12Resolver())
        val out = StringBuilder("[")
        var first = true
        for (document in yaml.loadAll(StringReader(text))) {
            if (!first) out.append(',')
            first = false
            appendJson(document, out)
        }
        return out.append(']').toString()
    }

    private class Yaml12Resolver : Resolver() {
        override fun addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF")
            addImplicitResolver(
                Tag.INT,
                Pattern.compile("^(?:[-+]?0b[0-1_]+|[-+]?0[0-7_]+|[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x[0-9a-fA-F_]+)$"),
                "-+0123456789",
            )
            addImplicitResolver(
                Tag.FLOAT,
                Pattern.compile(
                    "^(?:[-+]?[0-9][0-9_]*\\.[0-9_]*(?:[eE][-+]?[0-9]+)?|[-+]?[0-9][0-9_]*[eE][-+]?[0-9]+|" +
                        "\\.[0-9][0-9_]*(?:[eE][-+]?[0-9]+)?|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$",
                ),
                "-+0123456789.",
            )
            addImplicitResolver(Tag.MERGE, MERGE, "<")
            addImplicitResolver(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL)$"), "~nN")
            addImplicitResolver(Tag.NULL, EMPTY, null)
            addImplicitResolver(Tag.TIMESTAMP, TIMESTAMP, "0123456789")
        }
    }

    private fun appendJson(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean, is Int, is Long, is BigInteger -> out.append(value.toString())
            is Double -> {
                require(!value.isNaN() && !value.isInfinite()) { "json: unsupported value: ${formatFloat(value)}" }
                out.append(value.toString())
            }
            is Date -> out.append(quoteJson(Instant.ofEpochMilli(value.time).toString()))
            is ByteArray -> out.append(quoteJson(Base64.getEncoder().encodeToString(value)))
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    out.append(quoteJson(k.toString())).append(':')
                    appendJson(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    appendJson(item, out)
                }
                out.append(']')
            }
            else -> out.append(quoteJson(value.toString()))
        }
    }

    private fun quoteJson(s: String): String = UJson.write(Str(s), -1, false, false)

    // --- YAML emitter --------------------------------------------------------------------------------------------
    // Layout rule, derived from real yaml.v3 output: a block value under a key sits at base+4; a mapping that is a
    // sequence item aligns its keys after the "- " (dash column + 2) but its children still indent from the
    // sequence's own base.

    private fun emitMap(v: Obj, out: StringBuilder, keyCol: Int, base: Int, firstInline: Boolean) {
        val entries = objectEntries(v).sortedWith { a, b -> compareKeys(a.first, b.first) }
        entries.forEachIndexed { index, (key, value) ->
            if (!(index == 0 && firstInline)) out.append(" ".repeat(keyCol))
            out.append(scalar(Str(key))).append(':')
            when {
                value is Obj && !isEmpty(value) -> { out.append('\n'); emitMap(value, out, base + 4, base + 4, firstInline = false) }
                value is Arr && !isEmpty(value) -> { out.append('\n'); emitSeq(value, out, base + 4, firstInline = false) }
                value is Str && value.value().contains('\n') && literalOk(value.value()) -> {
                    out.append(' ').append(literalHeader(value.value())).append('\n')
                    literalBody(value.value(), base + 4, out)
                }
                else -> out.append(' ').append(scalar(value)).append('\n')
            }
        }
    }

    private fun emitSeq(v: Arr, out: StringBuilder, seqIndent: Int, firstInline: Boolean) {
        arrayItems(v).forEachIndexed { index, item ->
            if (!(index == 0 && firstInline)) out.append(" ".repeat(seqIndent))
            out.append("- ")
            when {
                item is Obj && !isEmpty(item) -> emitMap(item, out, seqIndent + 2, seqIndent, firstInline = true)
                item is Arr && !isEmpty(item) -> emitSeq(item, out, seqIndent + 2, firstInline = true)
                item is Str && item.value().contains('\n') && literalOk(item.value()) -> {
                    out.append(literalHeader(item.value())).append('\n')
                    literalBody(item.value(), seqIndent + 2, out)
                }
                else -> out.append(scalar(item)).append('\n')
            }
        }
    }

    private fun isEmpty(v: Obj) = objectEntries(v).isEmpty()
    private fun isEmpty(v: Arr) = arrayItems(v).isEmpty()

    private fun objectEntries(v: Obj): List<Pair<String, Value>> {
        val it = v.value().iterator()
        val result = ArrayList<Pair<String, Value>>()
        while (it.hasNext()) {
            val entry = it.next()
            @Suppress("UNCHECKED_CAST")
            result += (entry._1() as String) to (entry._2() as Value)
        }
        return result
    }

    private fun arrayItems(v: Arr): List<Value> {
        val buf = v.value()
        return (0 until buf.length()).map { buf.apply(it) as Value }
    }

    private fun scalar(v: Value): String = when (v) {
        `Null$`.`MODULE$` -> "null"
        is Bool -> if (v.value()) "true" else "false"
        is Num -> formatFloat(v.value())
        is Str -> quoteString(v.value())
        is Obj -> "{}"
        is Arr -> "[]"
        else -> error("unexpected JSON value $v")
    }

    /** Go's `strconv.FormatFloat(f, 'g', -1, 64)`, which yaml.v3 uses for every JSON number (they all decode as float64). */
    fun formatFloat(d: Double): String {
        if (d == 0.0) return if (1.0 / d < 0) "-0" else "0"
        if (d.isNaN()) return ".nan"
        if (d.isInfinite()) return if (d > 0) ".inf" else "-.inf"
        val bd = BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val decimalPoint = digits.length - bd.scale() // position of the decimal point relative to the digit string
        val exp = decimalPoint - 1
        val sign = if (d < 0) "-" else ""
        // shortest formatting: Go uses precision 6 for the %e-vs-%f decision regardless of digit count
        if (exp < -4 || exp >= 6) {
            val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
            val e = if (exp < 0) "-" else "+"
            return "$sign${mantissa}e$e${Math.abs(exp).toString().padStart(2, '0')}"
        }
        return sign + when {
            decimalPoint <= 0 -> "0." + "0".repeat(-decimalPoint) + digits
            decimalPoint >= digits.length -> digits + "0".repeat(decimalPoint - digits.length)
            else -> digits.substring(0, decimalPoint) + "." + digits.substring(decimalPoint)
        }
    }

    private val RESOLVES_TO_NON_STRING = Regex(
        "^(?:~|null|Null|NULL|true|True|TRUE|false|False|FALSE|y|Y|yes|Yes|YES|n|N|no|No|NO|on|On|ON|off|Off|OFF|" +
            "[-+]?(?:0|[1-9][0-9_,]*)|[-+]?0[0-7_,]+|[-+]?0b[01_]+|[-+]?0x[0-9a-fA-F_]+|0o[0-7_]+|" +
            "[-+]?(?:\\.[0-9]+|[0-9][0-9_]*(?:\\.[0-9_]*)?)(?:[eE][-+]?[0-9]+)?|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN)|" +
            "[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[Tt ].*)?)$",
    )

    private fun quoteString(s: String): String {
        if (s.isEmpty() || RESOLVES_TO_NON_STRING.matches(s)) return doubleQuote(s)
        if (s.any { it.code < 0x20 || it.code == 0x7f || it == '\u0085' || it == ' ' || it == ' ' || it == '﻿' }) return doubleQuote(s)
        if (plainAllowed(s)) return s
        return "'" + s.replace("'", "''") + "'"
    }

    private fun plainAllowed(s: String): Boolean {
        if (s.first() == ' ' || s.last() == ' ') return false
        val first = s.first()
        if (first in "&*!|>'\"%@`#,[]{}") return false
        if ((first == '-' || first == '?' || first == ':') && (s.length == 1 || s[1] == ' ')) return false
        if (s.contains(": ") || s.contains(" #") || s.endsWith(":")) return false
        return true
    }

    private fun doubleQuote(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\t' -> out.append("\\t")
            c == '\r' -> out.append("\\r")
            c == '\u0000' -> out.append("\\0")
            c.code < 0x20 || c.code == 0x7f -> out.append("\\x").append(c.code.toString(16).padStart(2, '0'))
            else -> out.append(c)
        }
        return out.append('"').toString()
    }

    /** Multi-line strings print as literal blocks unless a leading space/tab or odd characters make that unsafe. */
    private fun literalOk(s: String): Boolean =
        s.none { (it.code < 0x20 && it != '\n') || it.code == 0x7f } && !s.startsWith(" ") && !s.startsWith("\n") &&
            s.split('\n').none { it.endsWith(" ") || it.endsWith("\t") }

    private fun literalHeader(s: String): String = when {
        !s.endsWith("\n") -> "|-"
        s.endsWith("\n\n") -> "|+"
        else -> "|"
    }

    private fun literalBody(s: String, indent: Int, out: StringBuilder) {
        val lines = s.removeSuffix("\n").split('\n')
        for (line in lines) {
            if (line.isNotEmpty()) out.append(" ".repeat(indent)).append(line)
            out.append('\n')
        }
        if (s.endsWith("\n\n")) repeat(s.length - s.trimEnd('\n').length - 1) { out.append('\n') }
    }

    /** yaml.v3's `keyList.Less`: letters before digits ordering by rune, digit runs compared as numbers. */
    private fun compareKeys(a: String, b: String): Int {
        val ar = a.codePoints().toArray()
        val br = b.codePoints().toArray()
        var digits = false
        var i = 0
        while (i < ar.size && i < br.size) {
            if (ar[i] == br[i]) { digits = Character.isDigit(ar[i]); i++; continue }
            val al = Character.isLetter(ar[i])
            val bl = Character.isLetter(br[i])
            if (al && bl) return ar[i].compareTo(br[i])
            if (al || bl) return if (digits) (if (al) -1 else 1) else (if (bl) -1 else 1)
            var an = 0L
            var bn = 0L
            if (ar[i] == '0'.code || br[i] == '0'.code) {
                var j = i - 1
                while (j >= 0 && Character.isDigit(ar[j])) { if (ar[j] != '0'.code) { an = 1; bn = 1; break }; j-- }
            }
            var ai = i
            while (ai < ar.size && Character.isDigit(ar[ai])) { an = an * 10 + (ar[ai] - '0'.code); ai++ }
            var bi = i
            while (bi < br.size && Character.isDigit(br[bi])) { bn = bn * 10 + (br[bi] - '0'.code); bi++ }
            if (an != bn) return an.compareTo(bn)
            if (ai != bi) return ai.compareTo(bi)
            return ar[i].compareTo(br[i])
        }
        return ar.size.compareTo(br.size)
    }
}
