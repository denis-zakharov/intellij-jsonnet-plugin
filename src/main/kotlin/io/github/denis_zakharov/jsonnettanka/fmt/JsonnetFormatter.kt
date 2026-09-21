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

Ported to Kotlin from go-jsonnet v0.22.0 (internal/formatter/jsonnetfmt.go, commit 567b61a); modified.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/**
 * A port of go-jsonnet's `jsonnetfmt` (the formatter behind `jsonnetfmt` and `tk fmt`): parses to a
 * fodder-preserving AST, runs a fixed pipeline of passes, and prints the result. Pure Kotlin, no IDE dependencies.
 */
object JsonnetFormatter {
    /**
     * Formats [input]; throws [ParseError] if it is not valid Jsonnet, or so deeply nested that the recursive
     * passes would overflow the stack (go-jsonnet copes with e.g. 10,000 nested arrays thanks to Go's growable
     * stacks; the JVM's fixed stack does not, and refusing is better than crashing the caller).
     */
    @JvmStatic
    @JvmOverloads
    fun format(input: String, options: Options = Options.DEFAULT): String {
        try {
            val parsed = parseJsonnet(input)
            return formatNode(parsed.root, parsed.finalFodder, options)
        } catch (e: StackOverflowError) {
            throw ParseError("Expression is nested too deeply to format", 1, 1)
        }
    }

    private fun removeInitialNewlines(node: Node) {
        val f = openFodder(node)
        while (f.isNotEmpty() && f.elements[0].kind == FodderKind.LINE_END) f.elements.removeAt(0)
    }

    private fun removeExtraTrailingNewlines(finalFodder: Fodder) {
        if (finalFodder.isNotEmpty()) finalFodder.elements.last().blanks = 0
    }

    private fun formatNode(root: Node, finalFodder: Fodder, options: Options): String {
        var node = root

        fun run(pass: AstPass) {
            node = pass.file(node, finalFodder)
        }

        // Passes to enforce style on the AST.
        if (options.sortImports) node = sortImports(node)
        removeInitialNewlines(node)
        if (options.maxBlankLines > 0) run(EnforceMaxBlankLines(options))
        run(FixNewlines())
        if (options.rewriteTokens) {
            run(FixTrailingCommas())
            run(FixParens())
            if (options.useImplicitPlus) run(RemovePlusObject()) else run(AddPlusObject())
            run(NoRedundantSliceColon())
        }
        if (options.stripComments) {
            run(StripComments())
        } else if (options.stripAllButComments) {
            run(StripAllButComments())
        } else if (options.stripEverything) {
            run(StripEverything())
        }
        if (options.prettyFieldNames) run(PrettyFieldNames())
        if (options.stringStyle != StringStyle.LEAVE) run(EnforceStringStyle(options))
        if (options.commentStyle != CommentStyle.LEAVE) run(EnforceCommentStyle(options))
        if (options.indent > 0) FixIndentation(options).visitFile(node, finalFodder)
        removeExtraTrailingNewlines(finalFodder)

        val u = Unparser(options)
        u.unparse(node, false)
        u.fillFinal(finalFodder, true, false)
        val last = finalFodder.elements.lastOrNull()
        if (last == null || last.kind == FodderKind.INTERSTITIAL) {
            // Final whitespace is stripped at lexing time. If we didn't just output a new line in fillFinal, then
            // add a single new line to ensure Jsonnet files end with a new line.
            u.unparseNewline()
        }
        return u.toString()
    }
}
