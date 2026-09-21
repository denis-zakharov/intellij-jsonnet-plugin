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

Ported to Kotlin from go-jsonnet v0.22.0 (ast/ast.go, internal/ast/ast.go, commit 567b61a); modified:
only the node kinds the parser produces, no locations, no free-variable/desugaring state.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/**
 * A raw (undesugared) Jsonnet AST that keeps [Fodder]. [fodder] is the fodder before the node's first token; for
 * left-recursive nodes (Apply, Binary, Index, ...) it is empty and the real one lives on the leftmost descendant —
 * see [openFodder].
 */
sealed class Node(var fodder: Fodder)

class IfSpec(var expr: Node, var ifFodder: Fodder)

class ForSpec(
    var forFodder: Fodder,
    var varFodder: Fodder,
    var varName: String,
    var inFodder: Fodder,
    var expr: Node,
    var conditions: MutableList<IfSpec> = mutableListOf(),
    var outer: ForSpec? = null,
)

class CommaSeparatedExpr(var expr: Node, var commaFodder: Fodder = Fodder())

class NamedArgument(
    var nameFodder: Fodder,
    var name: String,
    var eqFodder: Fodder,
    var arg: Node,
    var commaFodder: Fodder,
)

class Arguments(
    val positional: MutableList<CommaSeparatedExpr> = mutableListOf(),
    val named: MutableList<NamedArgument> = mutableListOf(),
)

class Parameter(
    var nameFodder: Fodder,
    var name: String,
    var commaFodder: Fodder = Fodder(),
    var eqFodder: Fodder = Fodder(),
    var defaultArg: Node? = null,
)

class Apply(
    var target: Node,
    var fodderLeft: Fodder,
    val arguments: Arguments,
    var trailingComma: Boolean,
    var fodderRight: Fodder,
    var tailStrict: Boolean,
    var tailStrictFodder: Fodder,
) : Node(Fodder())

class ApplyBrace(var left: Node, var right: Node, fodder: Fodder = Fodder()) : Node(fodder)

class ArrayLit(
    val elements: MutableList<CommaSeparatedExpr>,
    var trailingComma: Boolean,
    var closeFodder: Fodder,
    fodder: Fodder,
) : Node(fodder)

class ArrayComp(
    var body: Node,
    var trailingCommaFodder: Fodder,
    var trailingComma: Boolean,
    val spec: ForSpec,
    var closeFodder: Fodder,
    fodder: Fodder,
) : Node(fodder)

class Assert(
    var cond: Node,
    var colonFodder: Fodder,
    var message: Node?,
    var semicolonFodder: Fodder,
    var rest: Node,
    fodder: Fodder,
) : Node(fodder)

enum class BinaryOp(val text: String, val precedence: Int) {
    MULT("*", 5),
    DIV("/", 5),
    PERCENT("%", 5),
    PLUS("+", 6),
    MINUS("-", 6),
    SHIFT_L("<<", 7),
    SHIFT_R(">>", 7),
    GREATER(">", 8),
    GREATER_EQ(">=", 8),
    LESS("<", 8),
    LESS_EQ("<=", 8),
    IN("in", 8),
    MANIFEST_EQUAL("==", 9),
    MANIFEST_UNEQUAL("!=", 9),
    BITWISE_AND("&", 10),
    BITWISE_XOR("^", 11),
    BITWISE_OR("|", 12),
    AND("&&", 13),
    OR("||", 14),
    ;

    companion object {
        val byText: Map<String, BinaryOp> = entries.associateBy { it.text }
    }
}

class Binary(var left: Node, var opFodder: Fodder, var op: BinaryOp, var right: Node, fodder: Fodder = Fodder()) : Node(fodder)

class Conditional(
    var cond: Node,
    var thenFodder: Fodder,
    var branchTrue: Node,
    var elseFodder: Fodder,
    var branchFalse: Node?,
    fodder: Fodder,
) : Node(fodder)

class Dollar(fodder: Fodder) : Node(fodder)

class ErrorExpr(var expr: Node, fodder: Fodder) : Node(fodder)

class FunctionExpr(
    var parenLeftFodder: Fodder,
    val parameters: MutableList<Parameter>,
    var trailingComma: Boolean,
    var parenRightFodder: Fodder,
    var body: Node,
    fodder: Fodder = Fodder(),
) : Node(fodder)

sealed class ImportNode(var file: LiteralString, fodder: Fodder) : Node(fodder)
class Import(file: LiteralString, fodder: Fodder) : ImportNode(file, fodder)
class ImportStr(file: LiteralString, fodder: Fodder) : ImportNode(file, fodder)
class ImportBin(file: LiteralString, fodder: Fodder) : ImportNode(file, fodder)

/** `target.id` (when [id] != null) or `target[index]`. [rightBracketFodder] doubles as the fodder before [id]. */
class Index(
    var target: Node,
    var leftBracketFodder: Fodder,
    var index: Node?,
    var rightBracketFodder: Fodder,
    var id: String?,
) : Node(Fodder())

class Slice(
    var target: Node,
    var leftBracketFodder: Fodder,
    var beginIndex: Node?,
    var endColonFodder: Fodder,
    var endIndex: Node?,
    var stepColonFodder: Fodder,
    var step: Node?,
    var rightBracketFodder: Fodder,
) : Node(Fodder())

class LocalBind(
    var varFodder: Fodder,
    var variable: String,
    var eqFodder: Fodder,
    var body: Node,
    var closeFodder: Fodder,
    var fn: FunctionExpr?,
)

class Local(val binds: MutableList<LocalBind>, var body: Node, fodder: Fodder) : Node(fodder)

class LiteralBoolean(val value: Boolean, fodder: Fodder) : Node(fodder)
class LiteralNull(fodder: Fodder) : Node(fodder)
class LiteralNumber(val originalString: String, fodder: Fodder) : Node(fodder)

enum class LiteralStringKind { SINGLE, DOUBLE, BLOCK, VERBATIM_DOUBLE, VERBATIM_SINGLE }

class LiteralString(
    var value: String,
    var kind: LiteralStringKind,
    var blockIndent: String = "",
    var blockTermIndent: String = "",
    fodder: Fodder,
) : Node(fodder)

enum class ObjectFieldKind { ASSERT, ID, EXPR, STR, LOCAL }

/** Field visibility: `::` hidden, `:` inherit, `:::` visible. */
enum class ObjectFieldHide { HIDDEN, INHERIT, VISIBLE }

class ObjectField(
    var kind: ObjectFieldKind,
    var hide: ObjectFieldHide,
    var superSugar: Boolean,
    var method: FunctionExpr?,
    var fodder1: Fodder,
    var expr1: Node?,
    var id: String?,
    var fodder2: Fodder,
    var opFodder: Fodder,
    var expr2: Node?,
    var expr3: Node?,
    var commaFodder: Fodder,
)

class ObjectLit(
    val fields: MutableList<ObjectField>,
    var trailingComma: Boolean,
    var closeFodder: Fodder,
    fodder: Fodder,
) : Node(fodder)

class ObjectComp(
    val fields: MutableList<ObjectField>,
    var trailingComma: Boolean,
    var trailingCommaFodder: Fodder,
    val spec: ForSpec,
    var closeFodder: Fodder,
    fodder: Fodder,
) : Node(fodder)

class Parens(var inner: Node, var closeFodder: Fodder, fodder: Fodder) : Node(fodder)

class SelfExpr(fodder: Fodder) : Node(fodder)

class SuperIndex(
    var dotFodder: Fodder,
    var index: Node?,
    var idFodder: Fodder,
    var id: String?,
    fodder: Fodder,
) : Node(fodder)

class InSuper(var index: Node, var inFodder: Fodder, var superFodder: Fodder) : Node(Fodder())

enum class UnaryOp(val text: String) {
    NOT("!"),
    BITWISE_NOT("~"),
    PLUS("+"),
    MINUS("-"),
    ;

    companion object {
        val byText: Map<String, UnaryOp> = entries.associateBy { it.text }
    }
}

class Unary(var op: UnaryOp, var expr: Node, fodder: Fodder) : Node(fodder)

class Var(val id: String, fodder: Fodder) : Node(fodder)

// ---------------------------------------------------------------------------
// Precedence (internal/ast/ast.go)

const val MIN_PRECEDENCE = 1
const val APPLY_PRECEDENCE = 2
const val UNARY_PRECEDENCE = 4
const val MAX_PRECEDENCE = 16

// ---------------------------------------------------------------------------
// Left-recursive helpers (internal/formatter/jsonnetfmt.go)

/** If [expr] is left recursive, its left-hand side; otherwise null. */
fun leftRecursive(expr: Node): Node? = when (expr) {
    is Apply -> expr.target
    is ApplyBrace -> expr.left
    is Binary -> expr.left
    is Index -> expr.target
    is InSuper -> expr.index
    is Slice -> expr.target
    else -> null
}

/** The transitive closure of [leftRecursive]. */
fun leftRecursiveDeep(expr: Node): Node {
    var last = expr
    var left = leftRecursive(expr)
    while (left != null) {
        last = left
        left = leftRecursive(last)
    }
    return last
}

/** The fodder before the first token of [node], however deep that token is. */
fun openFodder(node: Node): Fodder = leftRecursiveDeep(node).fodder

/** Go's `len(string)`: the formatter measures columns in UTF-8 bytes, so we must too to stay byte-exact. */
internal fun utf8Len(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        n += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                i++
                4
            }
            else -> 3
        }
        i++
    }
    return n
}
