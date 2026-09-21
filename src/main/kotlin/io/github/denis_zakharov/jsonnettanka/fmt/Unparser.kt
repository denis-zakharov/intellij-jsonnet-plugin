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

Ported to Kotlin from go-jsonnet v0.22.0 (internal/formatter/unparser.go, commit 567b61a); modified.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/** Prints an AST (with its fodder) back to source text. */
internal class Unparser(private val options: Options) {
    private val buf = StringBuilder()

    private fun write(str: String) {
        buf.append(str)
    }

    private fun writeSpaces(n: Int) {
        for (i in 0 until n) buf.append(' ')
    }

    /**
     * Pretty-prints fodder. [crowded] and [separateToken] control whether single spaces are added to keep tokens
     * from joining together in the output.
     *
     * The intuition of [crowded] is that the caller passes true if the last thing printed would crowd whatever
     * is printed here: after a `,` it is true, after a `(` it is false because we don't want the space.
     *
     * If [crowded] is true, a space is printed after any fodder, unless [separateToken] is false or the fodder
     * ended with a newline. If [crowded] is true and [separateToken] is false and the fodder begins with an
     * interstitial, the interstitial is prefixed with a single space but there is no space after it. If
     * [crowded] is false and [separateToken] is true, a space is only printed when the fodder ended with an
     * interstitial comment (which creates a crowded situation where there was not one before). If both are false
     * no space is printed after or before the fodder, even if the last fodder was an interstitial.
     */
    private fun fodderFill(fodder: Fodder, crowdedIn: Boolean, separateToken: Boolean, final: Boolean) {
        var crowded = crowdedIn
        var lastIndent = 0
        val elements = fodder.elements
        for ((idx, fod) in elements.withIndex()) {
            val skipTrailing = final && idx == elements.size - 1
            when (fod.kind) {
                FodderKind.PARAGRAPH -> {
                    for ((i, l) in fod.comment.withIndex()) {
                        // Do not indent empty lines (note: first line is never empty).
                        if (l.isNotEmpty()) {
                            // First line is already indented by previous fod.
                            if (i > 0) writeSpaces(lastIndent)
                            write(l)
                        }
                        write("\n")
                    }
                    if (!skipTrailing) {
                        for (i in 0 until fod.blanks) write("\n")
                        writeSpaces(fod.indent)
                    }
                    lastIndent = fod.indent
                    crowded = false
                }
                FodderKind.LINE_END -> {
                    if (fod.comment.isNotEmpty()) {
                        write("  ")
                        write(fod.comment[0])
                    }
                    write("\n")
                    if (!skipTrailing) {
                        for (i in 0 until fod.blanks) write("\n")
                        writeSpaces(fod.indent)
                    }
                    lastIndent = fod.indent
                    crowded = false
                }
                FodderKind.INTERSTITIAL -> {
                    if (crowded) write(" ")
                    write(fod.comment[0])
                    crowded = true
                }
            }
        }
        if (separateToken && crowded) write(" ")
    }

    private fun fill(fodder: Fodder, crowded: Boolean, separateToken: Boolean) =
        fodderFill(fodder, crowded, separateToken, false)

    fun fillFinal(fodder: Fodder, crowded: Boolean, separateToken: Boolean) =
        fodderFill(fodder, crowded, separateToken, true)

    private fun unparseSpecs(spec: ForSpec) {
        spec.outer?.let { unparseSpecs(it) }
        fill(spec.forFodder, true, true)
        write("for")
        fill(spec.varFodder, true, true)
        write(spec.varName)
        fill(spec.inFodder, true, true)
        write("in")
        unparse(spec.expr, true)
        for (cond in spec.conditions) {
            fill(cond.ifFodder, true, true)
            write("if")
            unparse(cond.expr, true)
        }
    }

    private fun unparseParams(fodderL: Fodder, params: List<Parameter>, trailingComma: Boolean, fodderR: Fodder) {
        fill(fodderL, false, false)
        write("(")
        var first = true
        for (param in params) {
            if (!first) write(",")
            fill(param.nameFodder, !first, true)
            write(param.name)
            param.defaultArg?.let {
                fill(param.eqFodder, false, false)
                write("=")
                unparse(it, false)
            }
            fill(param.commaFodder, false, false)
            first = false
        }
        if (trailingComma) write(",")
        fill(fodderR, false, false)
        write(")")
    }

    private fun unparseFieldParams(field: ObjectField) {
        field.method?.let { unparseParams(it.parenLeftFodder, it.parameters, it.trailingComma, it.parenRightFodder) }
    }

    private fun unparseFields(fields: List<ObjectField>, crowded: Boolean) {
        var first = true
        for (field in fields) {
            if (!first) write(",")

            // An aux function so we don't repeat ourselves for the 3 kinds of basic field.
            fun unparseFieldRemainder() {
                unparseFieldParams(field)
                fill(field.opFodder, false, false)
                if (field.superSugar) write("+")
                when (field.hide) {
                    ObjectFieldHide.INHERIT -> write(":")
                    ObjectFieldHide.HIDDEN -> write("::")
                    ObjectFieldHide.VISIBLE -> write(":::")
                }
                unparse(field.expr2!!, true)
            }

            when (field.kind) {
                ObjectFieldKind.LOCAL -> {
                    fill(field.fodder1, !first || crowded, true)
                    write("local")
                    fill(field.fodder2, true, true)
                    write(field.id!!)
                    unparseFieldParams(field)
                    fill(field.opFodder, true, true)
                    write("=")
                    unparse(field.expr2!!, true)
                }
                ObjectFieldKind.ID -> {
                    fill(field.fodder1, !first || crowded, true)
                    write(field.id!!)
                    unparseFieldRemainder()
                }
                ObjectFieldKind.STR -> {
                    unparse(field.expr1!!, !first || crowded)
                    unparseFieldRemainder()
                }
                ObjectFieldKind.EXPR -> {
                    fill(field.fodder1, !first || crowded, true)
                    write("[")
                    unparse(field.expr1!!, false)
                    fill(field.fodder2, false, false)
                    write("]")
                    unparseFieldRemainder()
                }
                ObjectFieldKind.ASSERT -> {
                    fill(field.fodder1, !first || crowded, true)
                    write("assert")
                    unparse(field.expr2!!, true)
                    field.expr3?.let {
                        fill(field.opFodder, true, true)
                        write(":")
                        unparse(it, true)
                    }
                }
            }

            first = false
            fill(field.commaFodder, false, false)
        }
    }

    fun unparse(expr: Node, crowded: Boolean) {
        if (leftRecursive(expr) == null) fill(expr.fodder, crowded, true)

        when (expr) {
            is Apply -> {
                unparse(expr.target, crowded)
                fill(expr.fodderLeft, false, false)
                write("(")
                var first = true
                for (arg in expr.arguments.positional) {
                    if (!first) write(",")
                    unparse(arg.expr, !first)
                    fill(arg.commaFodder, false, false)
                    first = false
                }
                for (arg in expr.arguments.named) {
                    if (!first) write(",")
                    fill(arg.nameFodder, !first, true)
                    write(arg.name)
                    write("=")
                    unparse(arg.arg, false)
                    fill(arg.commaFodder, false, false)
                    first = false
                }
                if (expr.trailingComma) write(",")
                fill(expr.fodderRight, false, false)
                write(")")
                if (expr.tailStrict) {
                    fill(expr.tailStrictFodder, true, true)
                    write("tailstrict")
                }
            }

            is ApplyBrace -> {
                unparse(expr.left, crowded)
                unparse(expr.right, true)
            }

            is ArrayLit -> {
                write("[")
                var first = true
                for (element in expr.elements) {
                    if (!first) write(",")
                    unparse(element.expr, !first || options.padArrays)
                    fill(element.commaFodder, false, false)
                    first = false
                }
                if (expr.trailingComma) write(",")
                fill(expr.closeFodder, expr.elements.isNotEmpty(), options.padArrays)
                write("]")
            }

            is ArrayComp -> {
                write("[")
                unparse(expr.body, options.padArrays)
                fill(expr.trailingCommaFodder, false, false)
                if (expr.trailingComma) write(",")
                unparseSpecs(expr.spec)
                fill(expr.closeFodder, true, options.padArrays)
                write("]")
            }

            is Assert -> {
                write("assert")
                unparse(expr.cond, true)
                expr.message?.let {
                    fill(expr.colonFodder, true, true)
                    write(":")
                    unparse(it, true)
                }
                fill(expr.semicolonFodder, false, false)
                write(";")
                unparse(expr.rest, true)
            }

            is Binary -> {
                unparse(expr.left, crowded)
                fill(expr.opFodder, true, true)
                write(expr.op.text)
                unparse(expr.right, true)
            }

            is Conditional -> {
                write("if")
                unparse(expr.cond, true)
                fill(expr.thenFodder, true, true)
                write("then")
                unparse(expr.branchTrue, true)
                expr.branchFalse?.let {
                    fill(expr.elseFodder, true, true)
                    write("else")
                    unparse(it, true)
                }
            }

            is Dollar -> write("$")

            is ErrorExpr -> {
                write("error")
                unparse(expr.expr, true)
            }

            is FunctionExpr -> {
                write("function")
                unparseParams(expr.parenLeftFodder, expr.parameters, expr.trailingComma, expr.parenRightFodder)
                unparse(expr.body, true)
            }

            is Import -> {
                write("import")
                unparse(expr.file, true)
            }
            is ImportStr -> {
                write("importstr")
                unparse(expr.file, true)
            }
            is ImportBin -> {
                write("importbin")
                unparse(expr.file, true)
            }

            is Index -> {
                unparse(expr.target, crowded)
                fill(expr.leftBracketFodder, false, false) // Can also be DotFodder
                val id = expr.id
                if (id != null) {
                    write(".")
                    fill(expr.rightBracketFodder, false, false) // IdFodder
                    write(id)
                } else {
                    write("[")
                    unparse(expr.index!!, false)
                    fill(expr.rightBracketFodder, false, false)
                    write("]")
                }
            }

            is Slice -> {
                unparse(expr.target, crowded)
                fill(expr.leftBracketFodder, false, false)
                write("[")
                expr.beginIndex?.let { unparse(it, false) }
                fill(expr.endColonFodder, false, false)
                write(":")
                expr.endIndex?.let { unparse(it, false) }
                if (expr.step != null || expr.stepColonFodder.isNotEmpty()) {
                    fill(expr.stepColonFodder, false, false)
                    write(":")
                    expr.step?.let { unparse(it, false) }
                }
                fill(expr.rightBracketFodder, false, false)
                write("]")
            }

            is InSuper -> {
                unparse(expr.index, true)
                fill(expr.inFodder, true, true)
                write("in")
                fill(expr.superFodder, true, true)
                write("super")
            }

            is Local -> {
                write("local")
                check(expr.binds.isNotEmpty()) { "INTERNAL ERROR: local with no binds" }
                var first = true
                for (bind in expr.binds) {
                    if (!first) write(",")
                    first = false
                    fill(bind.varFodder, true, true)
                    write(bind.variable)
                    bind.fn?.let { unparseParams(it.parenLeftFodder, it.parameters, it.trailingComma, it.parenRightFodder) }
                    fill(bind.eqFodder, true, true)
                    write("=")
                    unparse(bind.body, true)
                    fill(bind.closeFodder, false, false)
                }
                write(";")
                unparse(expr.body, true)
            }

            is LiteralBoolean -> write(if (expr.value) "true" else "false")

            is LiteralNumber -> write(expr.originalString)

            is LiteralString -> unparseLiteralString(expr)

            is LiteralNull -> write("null")

            is ObjectLit -> {
                write("{")
                unparseFields(expr.fields, options.padObjects)
                if (expr.trailingComma) write(",")
                fill(expr.closeFodder, expr.fields.isNotEmpty(), options.padObjects)
                write("}")
            }

            is ObjectComp -> {
                write("{")
                unparseFields(expr.fields, options.padObjects)
                if (expr.trailingComma) write(",")
                unparseSpecs(expr.spec)
                fill(expr.closeFodder, true, options.padObjects)
                write("}")
            }

            is Parens -> {
                write("(")
                unparse(expr.inner, false)
                fill(expr.closeFodder, false, false)
                write(")")
            }

            is SelfExpr -> write("self")

            is SuperIndex -> {
                write("super")
                fill(expr.dotFodder, false, false)
                val id = expr.id
                if (id != null) {
                    write(".")
                    fill(expr.idFodder, false, false)
                    write(id)
                } else {
                    write("[")
                    unparse(expr.index!!, false)
                    fill(expr.idFodder, false, false)
                    write("]")
                }
            }

            is Var -> write(expr.id)

            is Unary -> {
                write(expr.op.text)
                unparse(expr.expr, false)
            }
        }
    }

    private fun unparseLiteralString(node: LiteralString) {
        when (node.kind) {
            LiteralStringKind.DOUBLE -> {
                write("\"")
                // The original escape codes are still in the string.
                write(node.value)
                write("\"")
            }
            LiteralStringKind.SINGLE -> {
                write("'")
                write(node.value)
                write("'")
            }
            LiteralStringKind.BLOCK -> {
                val v = node.value
                write("|||")
                if (v[v.length - 1] != '\n') write("-")
                write("\n")
                if (v[0] != '\n') write(node.blockIndent)
                for (i in v.indices) {
                    val r = v[i]
                    // Formatter always outputs in unix mode.
                    if (r == '\r') continue
                    write(r.toString())
                    if (r == '\n' && i + 1 < v.length && v[i + 1] != '\n') write(node.blockIndent)
                }
                if (v[v.length - 1] != '\n') write("\n")
                write(node.blockTermIndent)
                write("|||")
            }
            LiteralStringKind.VERBATIM_DOUBLE -> {
                write("@\"")
                // Escapes were processed by the parser, so put them back in.
                for (r in node.value) {
                    if (r == '"') write("\"\"") else write(r.toString())
                }
                write("\"")
            }
            LiteralStringKind.VERBATIM_SINGLE -> {
                write("@'")
                for (r in node.value) {
                    if (r == '\'') write("''") else write(r.toString())
                }
                write("'")
            }
        }
    }

    fun unparseNewline() = write("\n")

    override fun toString(): String = buf.toString()
}
