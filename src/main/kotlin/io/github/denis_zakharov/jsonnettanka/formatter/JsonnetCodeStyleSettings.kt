package io.github.denis_zakharov.jsonnettanka.formatter

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import io.github.denis_zakharov.jsonnettanka.fmt.CommentStyle
import io.github.denis_zakharov.jsonnettanka.fmt.Options
import io.github.denis_zakharov.jsonnettanka.fmt.StringStyle

/**
 * Jsonnet-specific code style options — the knobs of `jsonnetfmt`. Defaults are go-jsonnet's `DefaultOptions()`, i.e.
 * what `jsonnetfmt` and `tk fmt` do. The indent width is the standard Jsonnet indent option (default 2).
 *
 * Fields are public and primitive because the platform (de)serializes them reflectively; the two style choices are
 * ints for the settings UI's combo boxes (see the `*_VALUES` constants).
 */
class JsonnetCodeStyleSettings(container: CodeStyleSettings) : CustomCodeStyleSettings("JsonnetCodeStyleSettings", container) {
    @JvmField var MAX_BLANK_LINES: Int = 2
    @JvmField var STRING_STYLE: Int = STRING_SINGLE
    @JvmField var COMMENT_STYLE: Int = COMMENT_SLASH
    @JvmField var PRETTY_FIELD_NAMES: Boolean = true
    @JvmField var PAD_ARRAYS: Boolean = false
    @JvmField var PAD_OBJECTS: Boolean = true
    @JvmField var SORT_IMPORTS: Boolean = true
    @JvmField var USE_IMPLICIT_PLUS: Boolean = true

    /** Leave `vendor/` and dot-files alone, like `tk fmt` does (see [JsonnetFormatExclusions]). */
    @JvmField var SKIP_VENDOR_AND_DOTFILES: Boolean = true

    /** The formatter options these settings stand for, with [indent] spaces per level. */
    fun toOptions(indent: Int): Options = Options(
        indent = indent,
        maxBlankLines = MAX_BLANK_LINES,
        stringStyle = when (STRING_STYLE) {
            STRING_DOUBLE -> StringStyle.DOUBLE
            STRING_LEAVE -> StringStyle.LEAVE
            else -> StringStyle.SINGLE
        },
        commentStyle = when (COMMENT_STYLE) {
            COMMENT_HASH -> CommentStyle.HASH
            COMMENT_LEAVE -> CommentStyle.LEAVE
            else -> CommentStyle.SLASH
        },
        prettyFieldNames = PRETTY_FIELD_NAMES,
        padArrays = PAD_ARRAYS,
        padObjects = PAD_OBJECTS,
        sortImports = SORT_IMPORTS,
        useImplicitPlus = USE_IMPLICIT_PLUS,
    )

    /** Like [toOptions] but only whitespace may change: what a caller with `canChangeWhiteSpaceOnly` needs. */
    fun toWhitespaceOnlyOptions(indent: Int): Options = Options.WHITESPACE_ONLY.copy(
        indent = indent,
        maxBlankLines = MAX_BLANK_LINES,
        padArrays = PAD_ARRAYS,
        padObjects = PAD_OBJECTS,
    )

    companion object {
        const val STRING_SINGLE = 0
        const val STRING_DOUBLE = 1
        const val STRING_LEAVE = 2
        const val COMMENT_SLASH = 0
        const val COMMENT_HASH = 1
        const val COMMENT_LEAVE = 2

        val STRING_STYLE_OPTIONS = arrayOf("Single quotes", "Double quotes", "Leave as is")
        val STRING_STYLE_VALUES = intArrayOf(STRING_SINGLE, STRING_DOUBLE, STRING_LEAVE)
        val COMMENT_STYLE_OPTIONS = arrayOf("// (slash)", "# (hash)", "Leave as is")
        val COMMENT_STYLE_VALUES = intArrayOf(COMMENT_SLASH, COMMENT_HASH, COMMENT_LEAVE)
    }
}
