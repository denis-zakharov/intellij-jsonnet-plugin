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

Ported to Kotlin from go-jsonnet v0.22.0 (formatter/formatter.go, internal/formatter/jsonnetfmt.go, commit 567b61a);
modified: `Options.WHITESPACE_ONLY` is new.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/** How the reformatter rewrites string literals. Strings containing `'` or `"` use whichever avoids escaping. */
enum class StringStyle { DOUBLE, SINGLE, LEAVE }

/** How the reformatter rewrites comments. `#!` hashbang comments are always left alone. */
enum class CommentStyle { HASH, SLASH, LEAVE }

/** Parameters that control the reformatter; the defaults are go-jsonnet's `DefaultOptions()` (what `tk fmt` uses). */
data class Options(
    /** Spaces per indentation level. */
    val indent: Int = 2,
    /** Max allowed consecutive blank lines; 0 means "no limit". */
    val maxBlankLines: Int = 2,
    val stringStyle: StringStyle = StringStyle.SINGLE,
    val commentStyle: CommentStyle = CommentStyle.SLASH,
    /** Only wrap field names in quotes when needed. */
    val prettyFieldNames: Boolean = true,
    /** `[ a ]` instead of `[a]`. */
    val padArrays: Boolean = false,
    /** `{ a }` instead of `{a}`. */
    val padObjects: Boolean = true,
    /** Sort the `local x = import '...'` block at the top of the file, in groups. */
    val sortImports: Boolean = true,
    /** Drop the `+` in `a + {...}` where it is not required. */
    val useImplicitPlus: Boolean = true,
    /**
     * Run the passes that add/remove tokens for canonical form: trailing commas, redundant parentheses, implicit
     * plus (both directions) and redundant slice colons. Off, together with the style options set to leave, gives
     * whitespace-only formatting.
     */
    val rewriteTokens: Boolean = true,
    val stripEverything: Boolean = false,
    val stripComments: Boolean = false,
    val stripAllButComments: Boolean = false,
) {
    companion object {
        val DEFAULT = Options()

        /**
         * Only whitespace changes (and comment reflow): no quote/comment-style rewrites, no import sorting, no
         * field-name, `+`, comma or paren rewrites. One exception is inherent to the lexer: digit separators
         * (`1_000`) are dropped, so callers that promise "whitespace only" should verify the result.
         */
        val WHITESPACE_ONLY = Options(
            stringStyle = StringStyle.LEAVE,
            commentStyle = CommentStyle.LEAVE,
            prettyFieldNames = false,
            sortImports = false,
            rewriteTokens = false,
        )
    }
}
