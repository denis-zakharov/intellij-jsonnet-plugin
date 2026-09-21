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

Ported to Kotlin from go-jsonnet v0.22.0 (internal/formatter/fix_indentation.go, commit 567b61a); modified.
Column arithmetic uses UTF-8 byte lengths ([utf8Len]) exactly as Go's `len()` does — hanging-indent alignment
must match jsonnetfmt byte for byte, including with non-ASCII identifiers, strings and comments.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/**
 * Sets the indentation of new-line fodder so that it follows the nested structure of the code.
 *
 * [Indent.lineUp] is what is generally used to indent after a new line; [Indent.base] helps derive a new
 * [Indent] when the indentation level increases; lineUp is generally > base. In
 *
 *     ____foobar(1,
 *     ___________2)
 *
 * the indent at the AST node for `2` has base == 4 and lineUp == 11.
 */
internal class FixIndentation(private val options: Options) {
    class Indent(val base: Int, val lineUp: Int)

    private var column = 0

    /** Sets the indent of all but the last new-line fodder to [allButLastIndent], and of the last to [lastIndent]. */
    private fun setIndents(fodder: Fodder, allButLastIndent: Int, lastIndent: Int) {
        // First count how many there are.
        val count = fodder.elements.count { it.kind != FodderKind.INTERSTITIAL }
        // Now set the indents.
        var i = 0
        for (f in fodder.elements) {
            if (f.kind != FodderKind.INTERSTITIAL) {
                f.indent = if (i + 1 < count) allButLastIndent else lastIndent
                i++
            }
        }
    }

    /**
     * Sets the indentation on the fodder elements and adjusts [column] as if the fodder was printed. See
     * `Unparser.fodderFill` for [crowdedIn] and [separateToken].
     */
    private fun fillLast(
        fodder: Fodder,
        crowdedIn: Boolean,
        separateToken: Boolean,
        allButLastIndent: Int,
        lastIndent: Int,
    ) {
        var crowded = crowdedIn
        setIndents(fodder, allButLastIndent, lastIndent)

        // A model of Unparser.fodderFill that just keeps track of the column counter.
        for (fod in fodder.elements) {
            when (fod.kind) {
                FodderKind.PARAGRAPH, FodderKind.LINE_END -> {
                    column = fod.indent
                    crowded = false
                }
                FodderKind.INTERSTITIAL -> {
                    if (crowded) column++
                    column += utf8Len(fod.comment[0])
                    crowded = true
                }
            }
        }
        if (separateToken && crowded) column++
    }

    /** Like [fillLast] but where the final and prior fodder get the same indent. */
    private fun fill(fodder: Fodder, crowded: Boolean, separateToken: Boolean, indent: Int) =
        fillLast(fodder, crowded, separateToken, indent, indent)

    /**
     * Indentation of sub-expressions: if the first sub-expression is on the same line as the current node,
     * subsequent ones line up; otherwise they go on the next line indented by `indent`.
     */
    private fun deriveIndent(firstFodder: Fodder, old: Indent, lineUp: Int): Indent {
        if (firstFodder.isEmpty() || firstFodder.elements[0].kind == FodderKind.INTERSTITIAL) {
            return Indent(old.base, lineUp)
        }
        // Reset
        return Indent(old.base + options.indent, old.base + options.indent)
    }

    /** Like [deriveIndent], but further indentations in sub-expressions are based from this column. */
    private fun newIndentStrong(firstFodder: Fodder, old: Indent, lineUp: Int): Indent {
        if (firstFodder.isEmpty() || firstFodder.elements[0].kind == FodderKind.INTERSTITIAL) {
            return Indent(lineUp, lineUp)
        }
        // Reset
        return Indent(old.base + options.indent, old.base + options.indent)
    }

    /**
     * If the first sub-expression is on the same line as the current node, subsequent ones line up; otherwise
     * they go on the next line with no additional indent.
     */
    private fun align(firstFodder: Fodder, old: Indent, lineUp: Int): Indent {
        if (firstFodder.isEmpty() || firstFodder.elements[0].kind == FodderKind.INTERSTITIAL) {
            return Indent(old.base, lineUp)
        }
        // Reset
        return old
    }

    /** Like [align], but further indentations in sub-expressions are based from this column. */
    private fun alignStrong(firstFodder: Fodder, old: Indent, lineUp: Int): Indent {
        if (firstFodder.isEmpty() || firstFodder.elements[0].kind == FodderKind.INTERSTITIAL) {
            return Indent(lineUp, lineUp)
        }
        // Reset
        return old
    }

    /** Does the given fodder contain at least one new line? */
    private fun hasNewLines(fodder: Fodder): Boolean = fodder.elements.any { it.kind != FodderKind.INTERSTITIAL }

    /** Indents comprehension forspecs. */
    private fun specs(spec: ForSpec, currIndent: Indent) {
        spec.outer?.let { specs(it, currIndent) }
        fill(spec.forFodder, true, true, currIndent.lineUp)
        column += 3 // for
        fill(spec.varFodder, true, true, currIndent.lineUp)
        column += utf8Len(spec.varName)
        fill(spec.inFodder, true, true, currIndent.lineUp)
        column += 2 // in
        val newIndent = deriveIndent(openFodder(spec.expr), currIndent, column)
        visit(spec.expr, newIndent, true)
        for (cond in spec.conditions) {
            fill(cond.ifFodder, true, true, currIndent.lineUp)
            column += 2 // if
            // go-jsonnet visits spec.Expr again here rather than cond.Expr; kept for byte-exact parity.
            val condIndent = deriveIndent(openFodder(spec.expr), currIndent, column)
            visit(spec.expr, condIndent, true)
        }
    }

    private fun params(
        fodderL: Fodder,
        params: List<Parameter>,
        trailingComma: Boolean,
        fodderR: Fodder,
        currIndent: Indent,
    ) {
        fill(fodderL, false, false, currIndent.lineUp)
        column++ // (
        val firstInside = if (params.isNotEmpty()) params[0].nameFodder else fodderR
        val newIndent = deriveIndent(firstInside, currIndent, column)
        var first = true
        for (param in params) {
            if (!first) column++ // ','
            fill(param.nameFodder, !first, true, newIndent.lineUp)
            column += utf8Len(param.name)
            param.defaultArg?.let {
                fill(param.eqFodder, false, false, newIndent.lineUp)
                // default arg, no spacing: x=e
                column++
                visit(it, newIndent, false)
            }
            fill(param.commaFodder, false, false, newIndent.lineUp)
            first = false
        }
        if (trailingComma) column++
        fillLast(fodderR, false, false, newIndent.lineUp, currIndent.lineUp)
        column++ // )
    }

    private fun fieldParams(field: ObjectField, currIndent: Indent) {
        field.method?.let { params(it.parenLeftFodder, it.parameters, it.trailingComma, it.parenRightFodder, currIndent) }
    }

    /**
     * Indents fields within an object. [currIndent] is the indent of the first field; [crowded] is whether the
     * first field is crowded (see `Unparser.fodderFill`).
     */
    private fun fields(fields: List<ObjectField>, currIndent: Indent, crowded: Boolean) {
        val newIndent = currIndent.lineUp
        for ((i, field) in fields.withIndex()) {
            if (i > 0) column++ // ','

            // An aux function so we don't repeat ourselves for the 3 kinds of basic field.
            fun unparseFieldRemainder() {
                fieldParams(field, currIndent)
                fill(field.opFodder, false, false, newIndent)
                if (field.superSugar) column++
                when (field.hide) {
                    ObjectFieldHide.INHERIT -> column++
                    ObjectFieldHide.HIDDEN -> column += 2
                    ObjectFieldHide.VISIBLE -> column += 3
                }
                val e2 = field.expr2!!
                visit(e2, deriveIndent(openFodder(e2), currIndent, column), true)
            }

            when (field.kind) {
                ObjectFieldKind.LOCAL -> {
                    fill(field.fodder1, i > 0 || crowded, true, currIndent.lineUp)
                    column += 5 // local
                    fill(field.fodder2, true, true, currIndent.lineUp)
                    column += utf8Len(field.id!!)
                    fieldParams(field, currIndent)
                    fill(field.opFodder, true, true, currIndent.lineUp)
                    column++ // =
                    val e2 = field.expr2!!
                    visit(e2, deriveIndent(openFodder(e2), currIndent, column), true)
                }
                ObjectFieldKind.ID -> {
                    fill(field.fodder1, i > 0 || crowded, true, newIndent)
                    column += utf8Len(field.id!!)
                    unparseFieldRemainder()
                }
                ObjectFieldKind.STR -> {
                    visit(field.expr1!!, currIndent, i > 0 || crowded)
                    unparseFieldRemainder()
                }
                ObjectFieldKind.EXPR -> {
                    fill(field.fodder1, i > 0 || crowded, true, newIndent)
                    column++ // [
                    visit(field.expr1!!, currIndent, false)
                    fill(field.fodder2, false, false, newIndent)
                    column++ // ]
                    unparseFieldRemainder()
                }
                ObjectFieldKind.ASSERT -> {
                    fill(field.fodder1, i > 0 || crowded, true, newIndent)
                    column += 6 // assert
                    val e2 = field.expr2!!
                    // + 1 for the space after the assert
                    val newIndent2 = deriveIndent(openFodder(e2), currIndent, column + 1)
                    visit(e2, currIndent, true)
                    field.expr3?.let {
                        fill(field.opFodder, true, true, newIndent2.lineUp)
                        column++ // ":"
                        visit(it, newIndent2, true)
                    }
                }
            }
            fill(field.commaFodder, false, false, newIndent)
        }
    }

    /** Has the logic common to all nodes. */
    fun visit(expr: Node, currIndent: Indent, crowded: Boolean) {
        val separateToken = leftRecursive(expr) == null
        fill(expr.fodder, crowded, separateToken, currIndent.lineUp)
        when (expr) {
            is Apply -> {
                val initFodder = openFodder(expr.target)
                var newColumn = column
                if (crowded) newColumn++
                val newIndent = align(initFodder, currIndent, newColumn)
                visit(expr.target, newIndent, crowded)
                fill(expr.fodderLeft, false, false, newIndent.lineUp)
                column++ // (
                var firstFodder = expr.fodderRight
                expr.arguments.named.firstOrNull()?.let { firstFodder = it.nameFodder }
                expr.arguments.positional.firstOrNull()?.let { firstFodder = openFodder(it.expr) }
                var strongIndent = false
                // Need to use strong indent if any of the arguments (except the first) are preceded by newlines.
                var first = true
                for (arg in expr.arguments.positional) {
                    if (first) {
                        first = false
                        continue
                    }
                    if (hasNewLines(openFodder(arg.expr))) strongIndent = true
                }
                for (arg in expr.arguments.named) {
                    if (first) {
                        first = false
                        continue
                    }
                    if (hasNewLines(arg.nameFodder)) strongIndent = true
                }
                val argIndent = if (strongIndent) {
                    newIndentStrong(firstFodder, currIndent, column)
                } else {
                    deriveIndent(firstFodder, currIndent, column)
                }

                first = true
                for (arg in expr.arguments.positional) {
                    if (!first) column++ // ","
                    visit(arg.expr, argIndent, !first)
                    fill(arg.commaFodder, false, false, argIndent.lineUp)
                    first = false
                }
                for (arg in expr.arguments.named) {
                    if (!first) column++ // ","
                    fill(arg.nameFodder, !first, false, argIndent.lineUp)
                    column += utf8Len(arg.name)
                    column++ // "="
                    visit(arg.arg, argIndent, false)
                    fill(arg.commaFodder, false, false, argIndent.lineUp)
                    first = false
                }
                if (expr.trailingComma) column++ // ","
                fillLast(expr.fodderRight, false, false, argIndent.lineUp, currIndent.base)
                column++ // )
                if (expr.tailStrict) {
                    fill(expr.tailStrictFodder, true, true, currIndent.base)
                    column += 10 // tailstrict
                }
            }

            is ApplyBrace -> {
                val initFodder = openFodder(expr.left)
                var newColumn = column
                if (crowded) newColumn++
                val newIndent = align(initFodder, currIndent, newColumn)
                visit(expr.left, newIndent, crowded)
                visit(expr.right, newIndent, true)
            }

            is ArrayLit -> {
                column++ // '['
                // First fodder element exists and is a newline
                val firstFodder = if (expr.elements.isNotEmpty()) openFodder(expr.elements[0].expr) else expr.closeFodder
                var newColumn = column
                if (options.padArrays) newColumn++
                var strongIndent = false
                // Need to use strong indent if there are not newlines before any of the sub-expressions
                for ((i, el) in expr.elements.withIndex()) {
                    if (i == 0) continue
                    if (hasNewLines(openFodder(el.expr))) strongIndent = true
                }

                val newIndent = if (strongIndent) {
                    newIndentStrong(firstFodder, currIndent, newColumn)
                } else {
                    deriveIndent(firstFodder, currIndent, newColumn)
                }

                for ((i, el) in expr.elements.withIndex()) {
                    if (i > 0) column++
                    visit(el.expr, newIndent, i > 0 || options.padArrays)
                    fill(el.commaFodder, false, false, newIndent.lineUp)
                }
                if (expr.trailingComma) column++

                // Handle penultimate newlines from expr.closeFodder if there are any.
                fillLast(expr.closeFodder, expr.elements.isNotEmpty(), options.padArrays, newIndent.lineUp, currIndent.base)
                column++ // ']'
            }

            is ArrayComp -> {
                column++ // [
                var newColumn = column
                if (options.padArrays) newColumn++
                val newIndent = deriveIndent(openFodder(expr.body), currIndent, newColumn)
                visit(expr.body, newIndent, options.padArrays)
                fill(expr.trailingCommaFodder, false, false, newIndent.lineUp)
                if (expr.trailingComma) column++ // ','
                specs(expr.spec, newIndent)
                fillLast(expr.closeFodder, true, options.padArrays, newIndent.lineUp, currIndent.base)
                column++ // ]
            }

            is Assert -> {
                column += 6 // assert
                // + 1 for the space after the assert
                val newIndent = deriveIndent(openFodder(expr.cond), currIndent, column + 1)
                visit(expr.cond, newIndent, true)
                expr.message?.let {
                    fill(expr.colonFodder, true, true, newIndent.lineUp)
                    column++ // ":"
                    visit(it, newIndent, true)
                }
                fill(expr.semicolonFodder, false, false, newIndent.lineUp)
                column++ // ";"
                visit(expr.rest, currIndent, true)
            }

            is Binary -> {
                val firstFodder = openFodder(expr.left)
                // Need to use strong indent in the case of
                //   A
                //   + B
                // or
                //   A +
                //   B
                var innerColumn = column
                if (crowded) innerColumn++
                val newIndent = if (hasNewLines(expr.opFodder) || hasNewLines(openFodder(expr.right))) {
                    alignStrong(firstFodder, currIndent, innerColumn)
                } else {
                    align(firstFodder, currIndent, innerColumn)
                }
                visit(expr.left, newIndent, crowded)
                fill(expr.opFodder, true, true, newIndent.lineUp)
                column += utf8Len(expr.op.text)
                // Don't calculate a new indent for here, because we like being able to do:
                // true &&
                // true &&
                // true
                visit(expr.right, newIndent, true)
            }

            is Conditional -> {
                column += 2 // if
                val condIndent = deriveIndent(openFodder(expr.cond), currIndent, column + 1)
                visit(expr.cond, condIndent, true)
                fill(expr.thenFodder, true, true, currIndent.base)
                column += 4 // then
                val trueIndent = deriveIndent(openFodder(expr.branchTrue), currIndent, column + 1)
                visit(expr.branchTrue, trueIndent, true)
                expr.branchFalse?.let {
                    fill(expr.elseFodder, true, true, currIndent.base)
                    column += 4 // else
                    val falseIndent = deriveIndent(openFodder(it), currIndent, column + 1)
                    visit(it, falseIndent, true)
                }
            }

            is Dollar -> column++ // $

            is ErrorExpr -> {
                column += 5 // error
                val newIndent = deriveIndent(openFodder(expr.expr), currIndent, column + 1)
                visit(expr.expr, newIndent, true)
            }

            is FunctionExpr -> {
                column += 8 // function
                params(expr.parenLeftFodder, expr.parameters, expr.trailingComma, expr.parenRightFodder, currIndent)
                val newIndent = deriveIndent(openFodder(expr.body), currIndent, column + 1)
                visit(expr.body, newIndent, true)
            }

            is Import -> visitImport(expr, 6, currIndent)
            is ImportStr -> visitImport(expr, 9, currIndent)
            is ImportBin -> visitImport(expr, 9, currIndent)

            is InSuper -> {
                visit(expr.index, currIndent, crowded)
                fill(expr.inFodder, true, true, currIndent.lineUp)
                column += 2 // in
                fill(expr.superFodder, true, true, currIndent.lineUp)
                column += 5 // super
            }

            is Index -> {
                visit(expr.target, currIndent, crowded)
                fill(expr.leftBracketFodder, false, false, currIndent.lineUp) // Can also be DotFodder
                val id = expr.id
                if (id != null) {
                    column++ // "."
                    val newIndent = deriveIndent(expr.rightBracketFodder, currIndent, column)
                    fill(expr.rightBracketFodder, false, false, newIndent.lineUp) // Can also be IdFodder
                    column += utf8Len(id)
                } else {
                    column++ // "["
                    val newIndent = deriveIndent(openFodder(expr.index!!), currIndent, column)
                    visit(expr.index!!, newIndent, false)
                    fillLast(expr.rightBracketFodder, false, false, newIndent.lineUp, currIndent.base)
                    column++ // "]"
                }
            }

            is Slice -> {
                visit(expr.target, currIndent, crowded)
                fill(expr.leftBracketFodder, false, false, currIndent.lineUp)
                column++ // "["
                var newIndent: Indent? = null
                expr.beginIndex?.let {
                    newIndent = deriveIndent(openFodder(it), currIndent, column)
                    visit(it, newIndent!!, false)
                }
                expr.endIndex?.let {
                    newIndent = deriveIndent(expr.endColonFodder, currIndent, column)
                    fill(expr.endColonFodder, false, false, newIndent!!.lineUp)
                    column++ // ":"
                    visit(it, newIndent!!, false)
                }
                expr.step?.let {
                    if (expr.endIndex == null) {
                        newIndent = deriveIndent(expr.endColonFodder, currIndent, column)
                        fill(expr.endColonFodder, false, false, newIndent!!.lineUp)
                        column++ // ":"
                    }
                    fill(expr.stepColonFodder, false, false, newIndent!!.lineUp)
                    column++ // ":"
                    visit(it, newIndent!!, false)
                }
                if (expr.beginIndex == null && expr.endIndex == null && expr.step == null) {
                    newIndent = deriveIndent(expr.endColonFodder, currIndent, column)
                    fill(expr.endColonFodder, false, false, newIndent!!.lineUp)
                    column++ // ":"
                }
                column++ // "]"
            }

            is Local -> {
                column += 5 // local
                check(expr.binds.isNotEmpty()) { "Not enough binds in local" }
                var first = true
                val newIndent = deriveIndent(expr.binds[0].varFodder, currIndent, column + 1)
                for (bind in expr.binds) {
                    if (!first) column++ // ','
                    first = false
                    fill(bind.varFodder, true, true, newIndent.lineUp)
                    column += utf8Len(bind.variable)
                    bind.fn?.let { params(it.parenLeftFodder, it.parameters, it.trailingComma, it.parenRightFodder, newIndent) }
                    fill(bind.eqFodder, true, true, newIndent.lineUp)
                    column++ // '='
                    val newIndent2 = deriveIndent(openFodder(bind.body), newIndent, column + 1)
                    visit(bind.body, newIndent2, true)
                    fillLast(bind.closeFodder, false, false, newIndent2.lineUp, currIndent.base)
                }
                column++ // ';'
                visit(expr.body, currIndent, true)
            }

            is LiteralBoolean -> column += if (expr.value) 4 else 5

            is LiteralNumber -> column += utf8Len(expr.originalString)

            is LiteralString -> when (expr.kind) {
                LiteralStringKind.DOUBLE, LiteralStringKind.SINGLE -> column += 2 + utf8Len(expr.value) // Include quotes
                LiteralStringKind.BLOCK -> {
                    expr.blockIndent = " ".repeat(currIndent.base + options.indent)
                    expr.blockTermIndent = " ".repeat(currIndent.base)
                    column = currIndent.base // blockTermIndent
                    column += 3 // always "|||" (never "|||-" because we're only accounting for block end)
                }
                LiteralStringKind.VERBATIM_SINGLE -> {
                    column += 3 // Include @, start and end quotes
                    expr.value.codePoints().forEach { column += if (it == '\''.code) 2 else 1 }
                }
                LiteralStringKind.VERBATIM_DOUBLE -> {
                    column += 3 // Include @, start and end quotes
                    expr.value.codePoints().forEach { column += if (it == '"'.code) 2 else 1 }
                }
            }

            is LiteralNull -> column += 4 // null

            is ObjectLit -> {
                column++ // '{'
                val firstFodder = if (expr.fields.isEmpty()) {
                    expr.closeFodder
                } else {
                    val f0 = expr.fields[0]
                    if (f0.kind == ObjectFieldKind.STR) openFodder(f0.expr1!!) else f0.fodder1
                }
                var newColumn = column
                if (options.padObjects) newColumn++
                val newIndent = deriveIndent(firstFodder, currIndent, newColumn)
                fields(expr.fields, newIndent, options.padObjects)
                if (expr.trailingComma) column++
                fillLast(expr.closeFodder, expr.fields.isNotEmpty(), options.padObjects, newIndent.lineUp, currIndent.base)
                column++ // '}'
            }

            is ObjectComp -> {
                column++ // '{'
                val firstFodder = if (expr.fields.isEmpty()) {
                    expr.closeFodder
                } else {
                    val f0 = expr.fields[0]
                    if (f0.kind == ObjectFieldKind.STR) openFodder(f0.expr1!!) else f0.fodder1
                }
                var newColumn = column
                if (options.padObjects) newColumn++
                val newIndent = deriveIndent(firstFodder, currIndent, newColumn)

                fields(expr.fields, newIndent, options.padObjects)
                if (expr.trailingComma) column++ // ','
                specs(expr.spec, newIndent)
                fillLast(expr.closeFodder, true, options.padObjects, newIndent.lineUp, currIndent.base)
                column++ // '}'
            }

            is Parens -> {
                column++ // (
                val newIndent = newIndentStrong(openFodder(expr.inner), currIndent, column)
                visit(expr.inner, newIndent, false)
                fillLast(expr.closeFodder, false, false, newIndent.lineUp, currIndent.base)
                column++ // )
            }

            is SelfExpr -> column += 4 // self

            is SuperIndex -> {
                column += 5 // super
                fill(expr.dotFodder, false, false, currIndent.lineUp)
                val id = expr.id
                if (id != null) {
                    column++ // "."
                    val newIndent = deriveIndent(expr.idFodder, currIndent, column)
                    fill(expr.idFodder, false, false, newIndent.lineUp)
                    column += utf8Len(id)
                } else {
                    column++ // "["
                    val newIndent = deriveIndent(openFodder(expr.index!!), currIndent, column)
                    visit(expr.index!!, newIndent, false)
                    fillLast(expr.idFodder, false, false, newIndent.lineUp, currIndent.base)
                    column++ // "]"
                }
            }

            is Unary -> {
                column += utf8Len(expr.op.text)
                val newIndent = deriveIndent(openFodder(expr.expr), currIndent, column)
                val leftIsDollar = leftRecursiveDeep(expr.expr) is Dollar
                visit(expr.expr, newIndent, leftIsDollar)
            }

            is Var -> column += utf8Len(expr.id)
        }
    }

    private fun visitImport(expr: ImportNode, keywordLen: Int, currIndent: Indent) {
        column += keywordLen // import / importstr / importbin
        val newIndent = deriveIndent(openFodder(expr.file), currIndent, column + 1)
        visit(expr.file, newIndent, true)
    }

    /** Corrects the whole file including the final fodder. */
    fun visitFile(body: Node, finalFodder: Fodder) {
        visit(body, Indent(0, 0), false)
        setIndents(finalFodder, 0, 0)
    }
}
