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

Ported to Kotlin from go-jsonnet v0.22.0 (internal/parser/lexer.go, commit 567b61a); modified:
scans UTF-16 chars instead of UTF-8 bytes (only matters for error columns), no source-map bookkeeping.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/** A lexing or parsing failure. [line] and [column] are 1-based; the column counts chars, not bytes. */
class ParseError(message: String, val line: Int, val column: Int) : Exception("$line:$column $message")

internal enum class TokenKind(val text: String) {
    // Symbols
    BRACE_L("\"{\""),
    BRACE_R("\"}\""),
    BRACKET_L("\"[\""),
    BRACKET_R("\"]\""),
    COMMA("\",\""),
    DOLLAR("\"$\""),
    DOT("\".\""),
    PAREN_L("\"(\""),
    PAREN_R("\")\""),
    SEMICOLON("\";\""),

    // Arbitrary length lexemes
    IDENTIFIER("IDENTIFIER"),
    NUMBER("NUMBER"),
    OPERATOR("OPERATOR"),
    STRING_BLOCK("STRING_BLOCK"),
    STRING_DOUBLE("STRING_DOUBLE"),
    STRING_SINGLE("STRING_SINGLE"),
    VERBATIM_STRING_DOUBLE("VERBATIM_STRING_DOUBLE"),
    VERBATIM_STRING_SINGLE("VERBATIM_STRING_SINGLE"),

    // Keywords
    ASSERT("assert"),
    ELSE("else"),
    ERROR("error"),
    FALSE("false"),
    FOR("for"),
    FUNCTION("function"),
    IF("if"),
    IMPORT("import"),
    IMPORT_STR("importstr"),
    IMPORT_BIN("importbin"),
    IN("in"),
    LOCAL("local"),
    NULL_LIT("null"),
    SELF("self"),
    SUPER("super"),
    TAIL_STRICT("tailstrict"),
    THEN("then"),
    TRUE("true"),

    /** Holds the fodder after the last real token. */
    END_OF_FILE("end of file"),
}

private val TOKENS_WITH_CONTENT = setOf(
    TokenKind.IDENTIFIER, TokenKind.NUMBER, TokenKind.OPERATOR, TokenKind.STRING_BLOCK, TokenKind.STRING_DOUBLE,
    TokenKind.STRING_SINGLE, TokenKind.VERBATIM_STRING_DOUBLE, TokenKind.VERBATIM_STRING_SINGLE,
)

internal class Token(
    val kind: TokenKind,
    /** Fodder that occurs before this token. */
    val fodder: Fodder,
    /** Content of the token if it is not a keyword. */
    val data: String,
    val stringBlockIndent: String,
    val stringBlockTermIndent: String,
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = when {
        data.isEmpty() -> kind.text
        kind in TOKENS_WITH_CONTENT -> "(${kind.text}, \"$data\")"
        else -> "\"$data\""
    }
}

private val KEYWORDS: Map<String, TokenKind> = mapOf(
    "assert" to TokenKind.ASSERT,
    "else" to TokenKind.ELSE,
    "error" to TokenKind.ERROR,
    "false" to TokenKind.FALSE,
    "for" to TokenKind.FOR,
    "function" to TokenKind.FUNCTION,
    "if" to TokenKind.IF,
    "import" to TokenKind.IMPORT,
    "importstr" to TokenKind.IMPORT_STR,
    "importbin" to TokenKind.IMPORT_BIN,
    "in" to TokenKind.IN,
    "local" to TokenKind.LOCAL,
    "null" to TokenKind.NULL_LIT,
    "self" to TokenKind.SELF,
    "super" to TokenKind.SUPER,
    "tailstrict" to TokenKind.TAIL_STRICT,
    "then" to TokenKind.THEN,
    "true" to TokenKind.TRUE,
)

private fun isUpper(r: Int) = r >= 'A'.code && r <= 'Z'.code
private fun isLower(r: Int) = r >= 'a'.code && r <= 'z'.code
private fun isNumber(r: Int) = r >= '0'.code && r <= '9'.code
private fun isIdentifierFirst(r: Int) = isUpper(r) || isLower(r) || r == '_'.code
private fun isIdentifier(r: Int) = isIdentifierFirst(r) || isNumber(r)
private fun isSymbol(r: Int) = r >= 0 && "!$:~+-&|^=<>*/%".indexOf(r.toChar()) >= 0
private fun isHorizontalWhitespace(r: Int) = r == ' '.code || r == '\t'.code || r == '\r'.code
private fun isWhitespace(r: Int) = r == '\n'.code || isHorizontalWhitespace(r)

/** True if [str] could be a valid (non-keyword) identifier. */
fun isValidIdentifier(str: String): Boolean {
    if (str.isEmpty()) return false
    for ((i, ch) in str.withIndex()) {
        val r = ch.code
        if (i == 0) {
            if (!isIdentifierFirst(r)) return false
        } else if (!isIdentifier(r)) {
            return false
        }
    }
    return KEYWORDS[str] == null
}

/** Strips whitespace from both ends of [s], but only up to [margin] on the left. */
private fun stripWhitespace(s: String, margin: Int): String {
    if (s.isEmpty()) return s
    var i = 0
    while (i < s.length && isHorizontalWhitespace(s[i].code) && i < margin) i++
    var j = s.length
    while (j > i && isHorizontalWhitespace(s[j - 1].code)) j--
    return s.substring(i, j)
}

/** Splits [s] by `\n`, stripping left (up to [margin]) and right whitespace from each line. */
private fun lineSplit(s: String, margin: Int): MutableList<String> {
    val ret = mutableListOf<String>()
    val buf = StringBuilder()
    for (ch in s) {
        if (ch == '\n') {
            ret.add(stripWhitespace(buf.toString(), margin))
            buf.setLength(0)
        } else {
            buf.append(ch)
        }
    }
    ret.add(stripWhitespace(buf.toString(), margin))
    return ret
}

/**
 * Checks that [b] has at least the same whitespace prefix as [a] and returns the amount of this whitespace,
 * otherwise 0. If [a] has no whitespace prefix, returns 0.
 */
private fun checkWhitespace(a: String, aFrom: Int, b: String, bFrom: Int): Int {
    var i = 0
    while (aFrom + i < a.length) {
        val ac = a[aFrom + i]
        if (ac != ' ' && ac != '\t') return i
        if (bFrom + i >= b.length) return 0
        if (ac != b[bFrom + i]) return 0
        i++
    }
    return i
}

private const val EOF = -1

internal class Lexer(private val input: String) {
    private val tokens = mutableListOf<Token>()

    private var fodder = Fodder()
    private var tokenStart = 0
    private var tokenStartLine = 1
    private var tokenStartCol = 1

    /** Was the last char the first non-whitespace char on its line? */
    private var freshLine = true

    private var pos = 0
    private var lineNo = 1
    private var lineStart = 0

    private fun next(): Int {
        if (pos >= input.length) return EOF
        val r = input[pos].code
        pos++
        if (r == '\n'.code) {
            lineStart = pos
            lineNo++
            freshLine = true
        } else if (freshLine) {
            if (!isWhitespace(r)) freshLine = false
        }
        return r
    }

    private fun acceptN(n: Int) {
        repeat(n) { next() }
    }

    private fun peek(): Int = if (pos >= input.length) EOF else input[pos].code

    private fun startsWith(prefix: String): Boolean = input.startsWith(prefix, pos)

    private fun resetTokenStart() {
        tokenStart = pos
        tokenStartLine = lineNo
        tokenStartCol = pos - lineStart + 1
    }

    private fun emitFullToken(kind: TokenKind, data: String, blockIndent: String, blockTermIndent: String) {
        tokens.add(Token(kind, fodder, data, blockIndent, blockTermIndent, tokenStartLine, tokenStartCol))
        fodder = Fodder()
    }

    private fun emitToken(kind: TokenKind) {
        emitFullToken(kind, input.substring(tokenStart, pos), "", "")
        resetTokenStart()
    }

    private fun addFodder(kind: FodderKind, blanks: Int, indent: Int, comment: List<String>) {
        fodder.elements.add(FodderElement(kind, blanks, indent, comment.toMutableList()))
    }

    private fun addFodderSafe(kind: FodderKind, blanks: Int, indent: Int, comment: List<String>) {
        fodderAppend(fodder, FodderElement(kind, blanks, indent, comment.toMutableList()))
    }

    private fun errorAt(msg: String, line: Int, col: Int) = ParseError(msg, line, col)

    private fun errorHere(msg: String) = ParseError(msg, lineNo, pos - lineStart + 1)

    /**
     * Consumes all whitespace and returns (number of `\n`, number of spaces after the last `\n`). Converts `\t`
     * to 8 spaces.
     */
    private fun lexWhitespace(): Pair<Int, Int> {
        var indent = 0
        var newLines = 0
        var r = peek()
        while (isWhitespace(r)) {
            next()
            when (r) {
                '\r'.code -> {}
                '\n'.code -> {
                    indent = 0
                    newLines++
                }
                ' '.code -> indent++
                // Only right at the beginning of lines; elsewhere it is stripped anyway.
                '\t'.code -> indent += 8
            }
            r = peek()
        }
        return newLines to indent
    }

    /** Consumes text until the end of the line; returns (text, blank lines after, next indent). */
    private fun lexUntilNewline(): Triple<String, Int, Int> {
        val buf = StringBuilder()
        var lastNonSpace = 0
        var r = peek()
        while (r != EOF && r != '\n'.code) {
            next()
            buf.append(r.toChar())
            if (!isHorizontalWhitespace(r)) lastNonSpace = buf.length
            r = peek()
        }
        buf.setLength(lastNonSpace)
        val text = buf.toString()

        // Consume the '\n' and following indent.
        val (newLines, indent) = lexWhitespace()
        val blanks = if (newLines > 0) newLines - 1 else 0
        return Triple(text, blanks, indent)
    }

    private fun lexNumber() {
        // https://www.json.org/img/number.png, with `_` digit separators. Negative numbers are a unary operator
        // applied to a literal so that `x-1` is not `x` `-1`.
        val numBegin = 0
        val numAfterZero = 1
        val numAfterOneToNine = 2
        val numAfterIntUnderscore = 3
        val numAfterDot = 4
        val numAfterDigit = 5
        val numAfterFracUnderscore = 6
        val numAfterE = 7
        val numAfterExpSign = 8
        val numAfterExpDigit = 9
        val numAfterExpUnderscore = 10

        fun junk(what: String, r: Int): Nothing =
            throw errorHere("Couldn't lex number, junk after $what: ${quoteRune(r)}")

        val cb = StringBuilder()
        var state = numBegin
        outer@ while (true) {
            val r = peek()
            val isDigit = r >= '0'.code && r <= '9'.code
            when (state) {
                numBegin -> when {
                    r == '0'.code -> state = numAfterZero
                    r >= '1'.code && r <= '9'.code -> state = numAfterOneToNine
                    else -> throw IllegalStateException("Couldn't lex number")
                }
                numAfterZero -> when (r) {
                    '.'.code -> state = numAfterDot
                    'e'.code, 'E'.code -> state = numAfterE
                    '_'.code -> throw errorHere("Couldn't lex number, _ not allowed after leading 0")
                    else -> break@outer
                }
                numAfterOneToNine -> when {
                    r == '.'.code -> state = numAfterDot
                    r == 'e'.code || r == 'E'.code -> state = numAfterE
                    isDigit -> state = numAfterOneToNine
                    r == '_'.code -> state = numAfterIntUnderscore
                    else -> break@outer
                }
                numAfterIntUnderscore -> if (isDigit) state = numAfterOneToNine else junk("'_'", r)
                numAfterDot -> if (isDigit) state = numAfterDigit else junk("decimal point", r)
                numAfterDigit -> when {
                    r == 'e'.code || r == 'E'.code -> state = numAfterE
                    isDigit -> state = numAfterDigit
                    r == '_'.code -> state = numAfterFracUnderscore
                    else -> break@outer
                }
                numAfterFracUnderscore -> if (isDigit) state = numAfterDigit else junk("'_'", r)
                numAfterE -> when {
                    r == '+'.code || r == '-'.code -> state = numAfterExpSign
                    isDigit -> state = numAfterExpDigit
                    else -> junk("'E'", r)
                }
                numAfterExpSign -> if (isDigit) state = numAfterExpDigit else junk("exponent sign", r)
                numAfterExpDigit -> when {
                    isDigit -> state = numAfterExpDigit
                    r == '_'.code -> state = numAfterExpUnderscore
                    else -> break@outer
                }
                numAfterExpUnderscore -> if (isDigit) state = numAfterExpDigit else junk("'_'", r)
            }
            if (r != '_'.code) cb.append(r.toChar())
            next()
        }
        emitFullToken(TokenKind.NUMBER, cb.toString(), "", "")
        resetTokenStart()
    }

    /** Go's `strconv.QuoteRuneToASCII`, close enough for an error message. */
    private fun quoteRune(r: Int): String = when {
        r == EOF -> "'\\uffff'"
        r in 0x20..0x7e -> "'${r.toChar()}'"
        else -> "'\\u%04x'".format(r)
    }

    private fun lexIdentifier() {
        var r = peek()
        check(isIdentifierFirst(r)) { "Unexpected character in lexIdentifier" }
        while (r != EOF) {
            if (!isIdentifier(r)) break
            next()
            r = peek()
        }
        emitToken(KEYWORDS[input.substring(tokenStart, pos)] ?: TokenKind.IDENTIFIER)
    }

    /**
     * Lexes a token that starts with a symbol: a `#`/`//`/`/* */` comment, a `|||` text block or an operator.
     */
    private fun lexSymbol() {
        // freshLine is reset by next() so cache it here.
        val freshLineAtStart = freshLine
        var r = next()

        // Single line comment
        if (r == '#'.code || (r == '/'.code && peek() == '/'.code)) {
            val (comment, blanks, indent) = lexUntilNewline()
            val k = if (freshLineAtStart) FodderKind.PARAGRAPH else FodderKind.LINE_END
            addFodder(k, blanks, indent, listOf(r.toChar() + comment))
            return
        }

        // C style comment (could be interstitial or paragraph comment)
        if (r == '/'.code && peek() == '*'.code) {
            val margin = pos - lineStart - 1
            val commentStartLine = tokenStartLine
            val commentStartCol = tokenStartCol

            next() // consume the initial '*'
            r = next()
            while (r != '*'.code || peek() != '/'.code) {
                if (r == EOF) throw errorAt("Multi-line comment has no terminating */", commentStartLine, commentStartCol)
                r = next()
            }
            next() // consume trailing '/'
            // Includes the "/*" and "*/".
            val comment = input.substring(tokenStart, pos)

            var (newLinesAfter, indentAfter) = lexWhitespace()
            if (!comment.contains('\n')) {
                addFodder(FodderKind.INTERSTITIAL, 0, 0, listOf(comment))
                if (newLinesAfter > 0) addFodder(FodderKind.LINE_END, newLinesAfter - 1, indentAfter, emptyList())
            } else {
                val lines = lineSplit(comment, margin)
                check(lines[0][0] == '/') { "Invalid parsing of C style comment $lines" }
                // Support paragraphs with * down the left-hand side: add a space to lines that start with '*'.
                val allStar = lines.all { it.isNotEmpty() && it[0] == '*' }
                if (allStar) {
                    for (i in lines.indices) {
                        if (lines[i][0] == '*') lines[i] = " " + lines[i]
                    }
                }
                if (newLinesAfter == 0) {
                    // Ensure a line end after the paragraph.
                    newLinesAfter = 1
                    indentAfter = 0
                }
                addFodderSafe(FodderKind.PARAGRAPH, newLinesAfter - 1, indentAfter, lines)
            }
            return
        }

        if (r == '|'.code && startsWith("||")) {
            val blockStartLine = tokenStartLine
            val blockStartCol = tokenStartCol
            acceptN(2) // skip "||"

            var chompTrailingNl = false
            if (peek() == '-'.code) {
                chompTrailingNl = true
                next()
            }

            val cb = StringBuilder()

            // Skip whitespace
            r = next()
            while (r == ' '.code || r == '\t'.code || r == '\r'.code) r = next()

            // Skip \n
            if (r != '\n'.code) throw errorAt("Text block requires new line after |||.", blockStartLine, blockStartCol)

            // Process leading blank lines before calculating stringBlockIndent
            r = peek()
            while (r == '\n'.code) {
                next()
                cb.append(r.toChar())
                r = peek()
            }
            var numWhiteSpace = checkWhitespace(input, pos, input, pos)
            val stringBlockIndent = input.substring(pos, pos + numWhiteSpace)
            if (numWhiteSpace == 0) {
                throw errorAt("Text block's first line must start with whitespace", blockStartLine, blockStartCol)
            }

            while (true) {
                check(numWhiteSpace > 0) { "Unexpected value for numWhiteSpace" }
                acceptN(numWhiteSpace)
                r = next()
                while (r != '\n'.code) {
                    if (r == EOF) throw errorAt("Unexpected EOF", blockStartLine, blockStartCol)
                    cb.append(r.toChar())
                    r = next()
                }
                cb.append('\n')

                // Skip any blank lines
                r = peek()
                while (r == '\n'.code) {
                    next()
                    cb.append(r.toChar())
                    r = peek()
                }

                // Look at the next line
                numWhiteSpace = checkWhitespace(stringBlockIndent, 0, input, pos)
                if (numWhiteSpace == 0) {
                    // End of the text block
                    val termIndent = StringBuilder()
                    r = peek()
                    while (r == ' '.code || r == '\t'.code) {
                        next()
                        termIndent.append(r.toChar())
                        r = peek()
                    }
                    if (!startsWith("|||")) throw errorAt("Text block not terminated with |||", blockStartLine, blockStartCol)
                    acceptN(3) // skip '|||'

                    var str = cb.toString()
                    if (chompTrailingNl) str = str.substring(0, str.length - 1)

                    emitFullToken(TokenKind.STRING_BLOCK, str, stringBlockIndent, termIndent.toString())
                    resetTokenStart()
                    return
                }
            }
        }

        // Assume any string of symbols is a single operator.
        r = peek()
        while (isSymbol(r)) {
            // Not allowed // in operators. (go-jsonnet's check is `startsWith("/")`, i.e. any '/' ends the run.)
            if (r == '/'.code) break
            // Not allowed ||| in operators (accounts for |||-)
            if (r == '|'.code && startsWith("||")) break
            next()
            r = peek()
        }

        // Operators are not allowed to end with + - ~ ! unless they are one rune long. So wind it back if we need
        // to, but stop at the first rune. (go-jsonnet reads the last char once, so an operator ending in one of
        // these always collapses to its first char.)
        val last = input[pos - 1]
        if (last == '+' || last == '-' || last == '~' || last == '!' || last == '$') {
            if (pos > tokenStart + 1) pos = tokenStart + 1
        }

        if (input.substring(tokenStart, pos) == "$") emitToken(TokenKind.DOLLAR) else emitToken(TokenKind.OPERATOR)
    }

    fun lex(): List<Token> {
        while (true) {
            val (newLines, indent) = lexWhitespace()
            // If it's the end of the file, discard final whitespace.
            if (peek() == EOF) {
                next()
                resetTokenStart()
                break
            }
            if (newLines > 0) {
                // Otherwise store whitespace in fodder.
                addFodder(FodderKind.LINE_END, newLines - 1, indent, emptyList())
            }
            resetTokenStart() // Don't include whitespace in actual token.
            var r = peek()
            when (r) {
                '{'.code -> { next(); emitToken(TokenKind.BRACE_L) }
                '}'.code -> { next(); emitToken(TokenKind.BRACE_R) }
                '['.code -> { next(); emitToken(TokenKind.BRACKET_L) }
                ']'.code -> { next(); emitToken(TokenKind.BRACKET_R) }
                ','.code -> { next(); emitToken(TokenKind.COMMA) }
                '.'.code -> { next(); emitToken(TokenKind.DOT) }
                '('.code -> { next(); emitToken(TokenKind.PAREN_L) }
                ')'.code -> { next(); emitToken(TokenKind.PAREN_R) }
                ';'.code -> { next(); emitToken(TokenKind.SEMICOLON) }

                in '0'.code..'9'.code -> lexNumber()

                '"'.code, '\''.code -> lexQuotedString(r)

                '@'.code -> lexVerbatimString()

                else -> {
                    if (isIdentifierFirst(r)) {
                        lexIdentifier()
                    } else if (isSymbol(r) || r == '#'.code) {
                        lexSymbol()
                    } else {
                        throw errorHere("Could not lex the character ${quoteRune(r)}")
                    }
                }
            }
        }
        // We are currently at the EOF. Emit a special token to capture any trailing fodder.
        emitToken(TokenKind.END_OF_FILE)
        return tokens
    }

    private fun lexQuotedString(quote: Int) {
        val startLine = lineNo
        val startCol = pos - lineStart + 1
        next()
        var r = next()
        while (true) {
            if (r == EOF) throw errorAt("Unterminated String", startLine, startCol)
            if (r == quote) {
                // Don't include the quotes in the token data
                val kind = if (quote == '"'.code) TokenKind.STRING_DOUBLE else TokenKind.STRING_SINGLE
                emitFullToken(kind, input.substring(tokenStart + 1, pos - 1), "", "")
                resetTokenStart()
                return
            }
            if (r == '\\'.code && peek() != EOF) next()
            r = next()
        }
    }

    private fun lexVerbatimString() {
        val startLine = lineNo
        val startCol = pos - lineStart + 1
        next()
        // Verbatim string literals. ' and " quoting is interpreted here, unlike non-verbatim strings where it is
        // done later by unescape; no information is lost by resolving the repeated quote into a single quote.
        val data = StringBuilder()
        val quot = next()
        val kind = when (quot) {
            '"'.code -> TokenKind.VERBATIM_STRING_DOUBLE
            '\''.code -> TokenKind.VERBATIM_STRING_SINGLE
            else -> throw errorAt("Couldn't lex verbatim string, junk after '@': $quot", startLine, startCol)
        }
        var r = next()
        while (true) {
            if (r == EOF) {
                throw errorAt("Unterminated String", startLine, startCol)
            } else if (r == quot) {
                if (peek() == quot) {
                    next()
                    data.append(r.toChar())
                } else {
                    emitFullToken(kind, data.toString(), "", "")
                    resetTokenStart()
                    return
                }
            } else {
                data.append(r.toChar())
            }
            r = next()
        }
    }
}
