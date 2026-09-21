package io.github.denis_zakharov.jsonnettanka.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JsonnetStringLiteralsTest {

    @Test
    fun `quoted strings decode their escapes`() {
        assertEquals("a\nb", JsonnetStringLiterals.decode("'a\\nb'"))
        assertEquals("say \"hi\"", JsonnetStringLiterals.decode("\"say \\\"hi\\\"\""))
        assertEquals("it's", JsonnetStringLiterals.decode("'it\\'s'"))
        assertEquals("a\\b", JsonnetStringLiterals.decode("'a\\\\b'"))
        assertEquals("é", JsonnetStringLiterals.decode("'\\u00e9'"))
    }

    @Test
    fun `verbatim strings only double their quote`() {
        assertEquals("it's \\n", JsonnetStringLiterals.decode("@'it''s \\n'"))
        assertEquals("say \"hi\"", JsonnetStringLiterals.decode("@\"say \"\"hi\"\"\""))
    }

    @Test
    fun `text blocks lose their indentation and end with a newline`() {
        val block = "|||\n      `dayOfYear` calculates the ordinal day of the year.\n\n        indented more\n    |||"
        assertEquals("`dayOfYear` calculates the ordinal day of the year.\n\n  indented more\n", JsonnetStringLiterals.decode(block))
    }

    @Test
    fun `malformed literals are not decoded`() {
        assertNull(JsonnetStringLiterals.decode("'unterminated"))
        assertNull(JsonnetStringLiterals.decode("'bad \\q escape'"))
        assertNull(JsonnetStringLiterals.decode("'\\u12'"))
        assertNull(JsonnetStringLiterals.decode("not a string"))
    }
}
