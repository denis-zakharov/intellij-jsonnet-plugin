package io.github.denis_zakharov.jsonnettanka.lang.lexer

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Standalone lexer tests — no IDE project fixture needed (Lexer is a plain
 * object). Full parser/PSI/reference testing with BasePlatformTestCase is
 * Phase 4's job (see plan §8); this covers the highest-risk lexer additions
 * from Phase 1: new keywords/operators, text blocks, verbatim strings.
 */
class JsonnetLexerTest {

    private fun tokenize(text: String): List<Pair<IElementType, String>> {
        val lexer = JsonnetLexerAdapter()
        lexer.start(text)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != com.intellij.psi.TokenType.WHITE_SPACE) {
                tokens += type to lexer.tokenText
            }
            lexer.advance()
        }
        return tokens
    }

    @Test
    fun `keywords and identifiers`() {
        val tokens = tokenize("local function if then else for in error assert importbin foo")
        assertEquals(
            listOf(
                JsonnetTypes.LOCAL_KW, JsonnetTypes.FUNCTION_KW, JsonnetTypes.IF_KW, JsonnetTypes.THEN_KW,
                JsonnetTypes.ELSE_KW, JsonnetTypes.FOR_KW, JsonnetTypes.IN_KW, JsonnetTypes.ERROR_KW,
                JsonnetTypes.ASSERT_KW, JsonnetTypes.IMPORTBIN_KW, JsonnetTypes.IDENTIFIER,
            ),
            tokens.map { it.first },
        )
    }

    @Test
    fun `field operators pick the longest match`() {
        val tokens = tokenize("a:b ::c +:d +::e +:::f :::g")
        val opsOnly = tokens.filter { it.first != JsonnetTypes.IDENTIFIER }.map { it.first }
        assertEquals(
            listOf(
                JsonnetTypes.COLON, JsonnetTypes.COLONCOLON, JsonnetTypes.PLUSCOLON,
                JsonnetTypes.PLUSCOLONCOLON, JsonnetTypes.PLUSCOLONCOLONCOLON, JsonnetTypes.COLONCOLONCOLON,
            ),
            opsOnly,
        )
    }

    @Test
    fun `dollar and comparison operators`() {
        val tokens = tokenize("\$ == != <= >= << >> && || in")
        assertEquals(
            listOf(
                JsonnetTypes.DOLLAR, JsonnetTypes.EQEQ, JsonnetTypes.NEQ, JsonnetTypes.LTE, JsonnetTypes.GTE,
                JsonnetTypes.SHL, JsonnetTypes.SHR, JsonnetTypes.ANDAND, JsonnetTypes.OROR, JsonnetTypes.IN_KW,
            ),
            tokens.map { it.first },
        )
    }

    @Test
    fun `verbatim strings with doubled-quote escapes`() {
        // Jsonnet source: @'it''s' @"a""b"  (doubled quote = an escaped literal quote)
        val input = "@'it''s' @\"a\"\"b\""
        val tokens = tokenize(input)
        assertEquals(listOf(JsonnetTypes.STRING, JsonnetTypes.STRING), tokens.map { it.first })
        assertEquals("@'it''s'", tokens[0].second)
        assertEquals("@\"a\"\"b\"", tokens[1].second)
    }

    @Test
    fun `text block is a single string token`() {
        val text = "|||\n  hello\n  world\n|||\n"
        val tokens = tokenize(text)
        assertEquals(1, tokens.size)
        assertEquals(JsonnetTypes.STRING, tokens[0].first)
        assertEquals("|||\n  hello\n  world\n|||", tokens[0].second)
    }

    @Test
    fun `closer line can carry trailing tokens like a comma`() {
        // Real Jsonnet style: the closing ||| is immediately followed by a comma.
        val tokens = tokenize("{ text: |||\n  hi\n|||, other: 1 }")
        val types = tokens.map { it.first }
        assertEquals(JsonnetTypes.STRING, types[3])
        assertEquals("|||\n  hi\n|||", tokens[3].second)
        assertEquals(JsonnetTypes.COMMA, types[4])
    }

    @Test
    fun `unterminated text block does not hang and does not merge into one string`() {
        // No closing ||| before EOF: malformed input degrades to plain punctuation
        // tokens rather than being specially recovered — the important property
        // is that tokenizing terminates and doesn't silently swallow real code.
        val tokens = tokenize("|||\n  hello\n")
        assertEquals(listOf(JsonnetTypes.OROR, JsonnetTypes.PIPE, JsonnetTypes.IDENTIFIER), tokens.map { it.first })
    }

    @Test
    fun `digit separators stay inside one number token`() {
        for (n in listOf("1_000", "1_0.5_0e1_0", "12_34_56", "1.0_1", "1e1_0", "0", "0.5")) {
            assertEquals(listOf(JsonnetTypes.NUMBER to n), tokenize(n), n)
        }
    }

    @Test
    fun `a separator must sit between digits`() {
        // Not part of the number: `1_` is a number followed by an identifier, exactly as before the separators existed.
        assertEquals(listOf(JsonnetTypes.NUMBER, JsonnetTypes.IDENTIFIER), tokenize("1_").map { it.first })
        assertEquals(listOf(JsonnetTypes.NUMBER, JsonnetTypes.DOT, JsonnetTypes.IDENTIFIER), tokenize("1._5").map { it.first })
    }
}
