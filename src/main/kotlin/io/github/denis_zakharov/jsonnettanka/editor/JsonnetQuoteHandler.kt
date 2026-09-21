package io.github.denis_zakharov.jsonnettanka.editor

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.TokenType
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes

/**
 * Typing `'` or `"` inserts the closing quote; typing the closing one steps over it (`SimpleTokenSetQuoteHandler`).
 *
 * The lexer has no token for an unterminated string: a lone `'` is a `BAD_CHARACTER`, and that is exactly what a freshly
 * typed opening quote is, so it is recognised here. Not after a word character (`it's`), where it is an apostrophe.
 * Text blocks and `@'...'` verbatim strings are not paired; they are rare and their delimiters aren't a single quote.
 */
class JsonnetQuoteHandler : SimpleTokenSetQuoteHandler(JsonnetTypes.STRING) {
    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (iterator.tokenType == TokenType.BAD_CHARACTER) {
            if (iterator.start != offset) return false
            val text = iterator.document.charsSequence
            if (text[offset] != '\'' && text[offset] != '"') return false
            return offset == 0 || !(text[offset - 1].isLetterOrDigit() || text[offset - 1] == '_')
        }
        return super.isOpeningQuote(iterator, offset)
    }

    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int): Boolean =
        // A quote that is a BAD_CHARACTER by itself has, by construction, no closing partner on its line.
        (iterator.tokenType == TokenType.BAD_CHARACTER && iterator.start == offset) || super.hasNonClosedLiteral(editor, iterator, offset)
}
