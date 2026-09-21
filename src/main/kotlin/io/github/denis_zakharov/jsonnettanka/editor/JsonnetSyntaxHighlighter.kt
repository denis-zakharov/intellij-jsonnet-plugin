package io.github.denis_zakharov.jsonnettanka.editor

import io.github.denis_zakharov.jsonnettanka.lang.lexer.JsonnetLexerAdapter
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SingleLazyInstanceSyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class JsonnetSyntaxHighlighter : SyntaxHighlighterBase() {

    companion object {
        val KEYWORD: TextAttributesKey = createTextAttributesKey("JSONNET_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val STRING: TextAttributesKey = createTextAttributesKey("JSONNET_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER: TextAttributesKey = createTextAttributesKey("JSONNET_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val COMMENT: TextAttributesKey = createTextAttributesKey("JSONNET_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val BRACES: TextAttributesKey = createTextAttributesKey("JSONNET_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val BRACKETS: TextAttributesKey = createTextAttributesKey("JSONNET_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
        val PARENS: TextAttributesKey = createTextAttributesKey("JSONNET_PARENS", DefaultLanguageHighlighterColors.PARENTHESES)
        val OPERATOR: TextAttributesKey = createTextAttributesKey("JSONNET_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val IDENTIFIER: TextAttributesKey = createTextAttributesKey("JSONNET_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
        val BAD_CHARACTER: TextAttributesKey = createTextAttributesKey("JSONNET_BAD_CHARACTER", com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER)

        private val KEYWORD_KEYS = arrayOf(KEYWORD)
        private val STRING_KEYS = arrayOf(STRING)
        private val NUMBER_KEYS = arrayOf(NUMBER)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val BRACES_KEYS = arrayOf(BRACES)
        private val BRACKETS_KEYS = arrayOf(BRACKETS)
        private val PARENS_KEYS = arrayOf(PARENS)
        private val OPERATOR_KEYS = arrayOf(OPERATOR)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val BAD_CHAR_KEYS = arrayOf(BAD_CHARACTER)
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()

        private val KEYWORDS = setOf(
            JsonnetTypes.LOCAL_KW, JsonnetTypes.IMPORT_KW, JsonnetTypes.IMPORTSTR_KW, JsonnetTypes.IMPORTBIN_KW,
            JsonnetTypes.TRUE_KW, JsonnetTypes.FALSE_KW, JsonnetTypes.NULL_KW,
            JsonnetTypes.SELF_KW, JsonnetTypes.SUPER_KW, JsonnetTypes.FUNCTION_KW,
            JsonnetTypes.IF_KW, JsonnetTypes.THEN_KW, JsonnetTypes.ELSE_KW,
            JsonnetTypes.FOR_KW, JsonnetTypes.IN_KW, JsonnetTypes.ERROR_KW, JsonnetTypes.ASSERT_KW,
        )

        private val OPERATORS = setOf(
            JsonnetTypes.ASSIGN, JsonnetTypes.COLON, JsonnetTypes.COLONCOLON, JsonnetTypes.COLONCOLONCOLON,
            JsonnetTypes.PLUSCOLON, JsonnetTypes.PLUSCOLONCOLON, JsonnetTypes.PLUSCOLONCOLONCOLON,
            JsonnetTypes.DOT, JsonnetTypes.DOLLAR,
            JsonnetTypes.OROR, JsonnetTypes.ANDAND, JsonnetTypes.PIPE, JsonnetTypes.CARET, JsonnetTypes.AMP,
            JsonnetTypes.EQEQ, JsonnetTypes.NEQ, JsonnetTypes.LTE, JsonnetTypes.GTE, JsonnetTypes.SHL, JsonnetTypes.SHR,
            JsonnetTypes.LT, JsonnetTypes.GT, JsonnetTypes.PLUS, JsonnetTypes.MINUS, JsonnetTypes.STAR,
            JsonnetTypes.SLASH, JsonnetTypes.PERCENT, JsonnetTypes.BANG, JsonnetTypes.TILDE,
        )
    }

    override fun getHighlightingLexer(): Lexer = JsonnetLexerAdapter()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when {
        tokenType == null -> EMPTY_KEYS
        tokenType in KEYWORDS -> KEYWORD_KEYS
        tokenType == JsonnetTypes.STRING -> STRING_KEYS
        tokenType == JsonnetTypes.NUMBER -> NUMBER_KEYS
        tokenType == JsonnetTypes.COMMENT -> COMMENT_KEYS
        tokenType == JsonnetTypes.LBRACE || tokenType == JsonnetTypes.RBRACE -> BRACES_KEYS
        tokenType == JsonnetTypes.LBRACK || tokenType == JsonnetTypes.RBRACK -> BRACKETS_KEYS
        tokenType == JsonnetTypes.LPAREN || tokenType == JsonnetTypes.RPAREN -> PARENS_KEYS
        tokenType in OPERATORS -> OPERATOR_KEYS
        tokenType == JsonnetTypes.IDENTIFIER -> IDENTIFIER_KEYS
        tokenType == com.intellij.psi.TokenType.BAD_CHARACTER -> BAD_CHAR_KEYS
        else -> EMPTY_KEYS
    }
}

class JsonnetSyntaxHighlighterFactory : SingleLazyInstanceSyntaxHighlighterFactory() {
    override fun createHighlighter(): com.intellij.openapi.fileTypes.SyntaxHighlighter = JsonnetSyntaxHighlighter()
}
