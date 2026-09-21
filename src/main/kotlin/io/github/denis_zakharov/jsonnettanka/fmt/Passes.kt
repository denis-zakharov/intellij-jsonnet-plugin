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

Ported to Kotlin from go-jsonnet v0.22.0 (the internal/formatter passes other than unparser.go and fix_indentation.go,
commit 567b61a); modified: passes return replacement nodes instead of writing through slot pointers.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

// ---------------------------------------------------------------------------
// enforce_max_blank_lines.go

/** Ensures there are not too many blank lines in the code. */
class EnforceMaxBlankLines(private val options: Options) : AstPass() {
    override fun fodderElement(element: FodderElement, ctx: Any?) {
        if (element.kind != FodderKind.INTERSTITIAL) {
            if (element.blanks > options.maxBlankLines) element.blanks = options.maxBlankLines
        }
    }
}

// ---------------------------------------------------------------------------
// fix_newlines.go

/**
 * Adds newlines inside complex structures (arrays, objects etc.).
 *
 * A structure is either expanded (newlines in all the designated places) or unexpanded (in none of them). It only
 * looks shallowly at the AST, so there may be newlines deeper that don't affect expanding:
 *
 *     [{
 *         'a': 'b',
 *         'c': 'd',
 *     }]
 *
 * The outer array can stay unexpanded because there are no newlines between the square brackets and the braces.
 */
class FixNewlines : AstPass() {
    override fun onArray(node: ArrayLit, ctx: Any?) {
        var shouldExpand = false
        for (element in node.elements) {
            if (fodderCountNewlines(openFodder(element.expr)) > 0) shouldExpand = true
        }
        if (fodderCountNewlines(node.closeFodder) > 0) shouldExpand = true
        if (shouldExpand) {
            for (element in node.elements) fodderEnsureCleanNewline(openFodder(element.expr))
            fodderEnsureCleanNewline(node.closeFodder)
        }
        super.onArray(node, ctx)
    }

    override fun onObject(node: ObjectLit, ctx: Any?) {
        var shouldExpand = false
        for (field in node.fields) {
            if (fodderCountNewlines(objectFieldOpenFodder(field)) > 0) shouldExpand = true
        }
        if (fodderCountNewlines(node.closeFodder) > 0) shouldExpand = true
        if (shouldExpand) {
            for (field in node.fields) fodderEnsureCleanNewline(objectFieldOpenFodder(field))
            fodderEnsureCleanNewline(node.closeFodder)
        }
        super.onObject(node, ctx)
    }

    override fun onLocal(node: Local, ctx: Any?) {
        var shouldExpand = false
        for (bind in node.binds) {
            if (fodderCountNewlines(bind.varFodder) > 0) shouldExpand = true
        }
        if (shouldExpand) {
            for ((i, bind) in node.binds.withIndex()) {
                if (i > 0) fodderEnsureCleanNewline(bind.varFodder)
            }
        }
        super.onLocal(node, ctx)
    }

    private fun shouldExpandSpec(spec: ForSpec): Boolean {
        var shouldExpand = false
        spec.outer?.let { shouldExpand = shouldExpandSpec(it) }
        if (fodderCountNewlines(spec.forFodder) > 0) shouldExpand = true
        for (ifSpec in spec.conditions) {
            if (fodderCountNewlines(ifSpec.ifFodder) > 0) shouldExpand = true
        }
        return shouldExpand
    }

    private fun ensureSpecExpanded(spec: ForSpec) {
        spec.outer?.let { ensureSpecExpanded(it) }
        fodderEnsureCleanNewline(spec.forFodder)
        for (cond in spec.conditions) fodderEnsureCleanNewline(cond.ifFodder)
    }

    override fun onArrayComp(node: ArrayComp, ctx: Any?) {
        var shouldExpand = false
        if (fodderCountNewlines(openFodder(node.body)) > 0) shouldExpand = true
        if (shouldExpandSpec(node.spec)) shouldExpand = true
        if (fodderCountNewlines(node.closeFodder) > 0) shouldExpand = true
        if (shouldExpand) {
            fodderEnsureCleanNewline(openFodder(node.body))
            ensureSpecExpanded(node.spec)
            fodderEnsureCleanNewline(node.closeFodder)
        }
        super.onArrayComp(node, ctx)
    }

    override fun onObjectComp(node: ObjectComp, ctx: Any?) {
        var shouldExpand = false
        for (field in node.fields) {
            if (fodderCountNewlines(objectFieldOpenFodder(field)) > 0) shouldExpand = true
        }
        if (shouldExpandSpec(node.spec)) shouldExpand = true
        if (fodderCountNewlines(node.closeFodder) > 0) shouldExpand = true
        if (shouldExpand) {
            for (field in node.fields) fodderEnsureCleanNewline(objectFieldOpenFodder(field))
            ensureSpecExpanded(node.spec)
            fodderEnsureCleanNewline(node.closeFodder)
        }
        super.onObjectComp(node, ctx)
    }

    override fun onParens(node: Parens, ctx: Any?) {
        var shouldExpand = false
        if (fodderCountNewlines(openFodder(node.inner)) > 0) shouldExpand = true
        if (fodderCountNewlines(node.closeFodder) > 0) shouldExpand = true
        if (shouldExpand) {
            fodderEnsureCleanNewline(openFodder(node.inner))
            fodderEnsureCleanNewline(node.closeFodder)
        }
        super.onParens(node, ctx)
    }

    /**
     *     f(1, 2,
     *       3)      =>   f(1,
     *                      2,
     *                      3)
     *
     *     foo(
     *         1, 2, 3)   =>   foo(
     *                             1, 2, 3
     *                         )
     */
    override fun parameters(l: Fodder, params: MutableList<Parameter>, r: Fodder, ctx: Any?) {
        var shouldExpandBetween = false
        var shouldExpandNearParens = false
        var first = true
        for (param in params) {
            if (fodderCountNewlines(param.nameFodder) > 0) {
                if (first) shouldExpandNearParens = true else shouldExpandBetween = true
            }
            first = false
        }
        if (fodderCountNewlines(r) > 0) shouldExpandNearParens = true
        first = true
        for (param in params) {
            if ((first && shouldExpandNearParens) || (!first && shouldExpandBetween)) {
                fodderEnsureCleanNewline(param.nameFodder)
            }
            first = false
        }
        if (shouldExpandNearParens) fodderEnsureCleanNewline(r)
        super.parameters(l, params, r, ctx)
    }

    /** Same shape as [parameters], for call arguments. */
    override fun arguments(l: Fodder, args: Arguments, r: Fodder, ctx: Any?) {
        var shouldExpandBetween = false
        var shouldExpandNearParens = false
        var first = true
        for (arg in args.positional) {
            if (fodderCountNewlines(openFodder(arg.expr)) > 0) {
                if (first) shouldExpandNearParens = true else shouldExpandBetween = true
            }
            first = false
        }
        for (arg in args.named) {
            if (fodderCountNewlines(arg.nameFodder) > 0) {
                if (first) shouldExpandNearParens = true else shouldExpandBetween = true
            }
            first = false
        }
        if (fodderCountNewlines(r) > 0) shouldExpandNearParens = true
        first = true
        for (arg in args.positional) {
            if ((first && shouldExpandNearParens) || (!first && shouldExpandBetween)) {
                fodderEnsureCleanNewline(openFodder(arg.expr))
            }
            first = false
        }
        for (arg in args.named) {
            if ((first && shouldExpandNearParens) || (!first && shouldExpandBetween)) {
                fodderEnsureCleanNewline(arg.nameFodder)
            }
            first = false
        }
        if (shouldExpandNearParens) fodderEnsureCleanNewline(r)
        super.arguments(l, args, r, ctx)
    }
}

private fun objectFieldOpenFodder(field: ObjectField): Fodder =
    // A STR field's expr1 can only ever be a LiteralString, so openFodder returns without recursing.
    if (field.kind == ObjectFieldKind.STR) openFodder(field.expr1!!) else field.fodder1

// ---------------------------------------------------------------------------
// fix_trailing_commas.go

private fun containsNewline(fodder: Fodder): Boolean = fodder.elements.any { it.kind != FodderKind.INTERSTITIAL }

/** Ensures trailing commas are present when a list is split over several lines (and absent otherwise). */
class FixTrailingCommas : AstPass() {
    /** Returns the new value of the trailing-comma flag. */
    private fun fixComma(lastCommaFodder: Fodder, trailingComma: Boolean, closeFodder: Fodder): Boolean {
        val needComma = containsNewline(closeFodder) || containsNewline(lastCommaFodder)
        if (trailingComma) {
            if (!needComma) {
                // Remove it but keep fodder.
                fodderMoveFront(closeFodder, lastCommaFodder)
                return false
            } else if (containsNewline(lastCommaFodder)) {
                // The comma is needed but currently is separated by a newline.
                fodderMoveFront(closeFodder, lastCommaFodder)
            }
        } else if (needComma) {
            // There was no comma, but there was a newline before the ] so add a comma.
            return true
        }
        return trailingComma
    }

    private fun removeComma(lastCommaFodder: Fodder, trailingComma: Boolean, closeFodder: Fodder): Boolean {
        if (trailingComma) {
            // Remove it but keep fodder.
            fodderMoveFront(closeFodder, lastCommaFodder)
            return false
        }
        return trailingComma
    }

    override fun onArray(node: ArrayLit, ctx: Any?) {
        if (node.elements.isEmpty()) return // No comma present and none can be added.
        node.trailingComma = fixComma(node.elements.last().commaFodder, node.trailingComma, node.closeFodder)
        super.onArray(node, ctx)
    }

    override fun onArrayComp(node: ArrayComp, ctx: Any?) {
        node.trailingComma = removeComma(node.trailingCommaFodder, node.trailingComma, node.spec.forFodder)
        super.onArrayComp(node, ctx)
    }

    override fun onObject(node: ObjectLit, ctx: Any?) {
        if (node.fields.isEmpty()) return // No comma present and none can be added.
        node.trailingComma = fixComma(node.fields.last().commaFodder, node.trailingComma, node.closeFodder)
        super.onObject(node, ctx)
    }

    override fun onObjectComp(node: ObjectComp, ctx: Any?) {
        node.trailingComma = removeComma(node.fields.last().commaFodder, node.trailingComma, node.spec.forFodder)
        super.onObjectComp(node, ctx)
    }
}

// ---------------------------------------------------------------------------
// fix_parens.go

/** Replaces `((e))` with `(e)`. */
class FixParens : AstPass() {
    override fun onParens(node: Parens, ctx: Any?) {
        val innerParens = node.inner
        if (innerParens is Parens) {
            node.inner = innerParens.inner
            fodderMoveFront(openFodder(node), innerParens.fodder)
            fodderMoveFront(node.closeFodder, innerParens.closeFodder)
        }
        super.onParens(node, ctx)
    }
}

// ---------------------------------------------------------------------------
// no_redundant_slice_colon.go

/** Preserves fodder in the case of `arr[1::]` being formatted as `arr[1:]`. */
class NoRedundantSliceColon : AstPass() {
    override fun onSlice(node: Slice, ctx: Any?) {
        if (node.step == null && node.stepColonFodder.isNotEmpty()) {
            fodderMoveFront(node.rightBracketFodder, node.stepColonFodder)
        }
        super.onSlice(node, ctx)
    }
}

// ---------------------------------------------------------------------------
// remove_plus_object.go

/** Replaces `e + { ... }` with the implicit-plus `e { ... }` in some situations. */
class RemovePlusObject : AstPass() {
    override fun visit(node: Node, ctx: Any?): Node {
        var result = node
        if (node is Binary) {
            // Could relax this to allow more ASTs on the LHS but this seems OK for now.
            val left = node.left
            val right = node.right
            if ((left is Var || left is Index) && right is ObjectLit && node.op == BinaryOp.PLUS) {
                fodderMoveFront(right.fodder, node.opFodder)
                result = ApplyBrace(left, right, node.fodder)
            }
        }
        return super.visit(result, ctx)
    }
}

// ---------------------------------------------------------------------------
// add_plus_object.go

private class PlusContext(val parent: Node?)

/**
 * Replaces `e {}` with `e + {}`. The context is the parent node, used to decide whether the replacement needs
 * parentheses to keep the same parse tree:
 *
 *     {a:1} {b:2}          no parens (top-level)
 *     {a:1} {b:2}(42)      parens: Apply binds tighter than +
 *     {a:1} {b:2}.a        parens: same for Index
 *     {a:1} + {b:2} {c:3}  parens: on the right of a left-associative +
 *     {a:1} {b:2} + {c:3}  none: on the left of +
 *     + 42 {b:2}           parens: the parse tree is (+ (implicit-plus 42 {b:2}))
 */
class AddPlusObject : AstPass() {
    override fun baseContext(): Any? = PlusContext(null)

    override fun visit(node: Node, ctx: Any?): Node {
        var result = node
        if (node is ApplyBrace) {
            val binary = Binary(node.left, Fodder(), BinaryOp.PLUS, node.right, node.fodder)
            result = binary
            val parent = (ctx as? PlusContext)?.parent
            if (parent != null) {
                var needsParens = false
                when (parent) {
                    is ApplyBrace -> error("parent implicit-plus should already have been replaced with explicit plus")
                    // Apply/Index bind tighter than Binary, so we need parens if we are the target (arguments are delimited).
                    is Apply -> if (parent.target === node) needsParens = true
                    is Index -> if (parent.target === node) needsParens = true
                    // We can only be on the left, because InSuper RHS is always exactly `super`.
                    is InSuper -> needsParens = BinaryOp.IN.precedence <= PLUS_PRECEDENCE
                    is Binary -> {
                        val opPrec = parent.op.precedence
                        needsParens = if (parent.left === node) opPrec < PLUS_PRECEDENCE else opPrec <= PLUS_PRECEDENCE
                    }
                    is Unary -> needsParens = UNARY_PRECEDENCE < PLUS_PRECEDENCE
                    else -> {}
                }
                if (needsParens) {
                    result = Parens(binary, Fodder(), binary.fodder)
                    binary.fodder = Fodder()
                }
            }
        }
        // Note we visit starting from the replacement node, we don't go directly to its children.
        return super.visit(result, PlusContext(result))
    }

    private companion object {
        val PLUS_PRECEDENCE = BinaryOp.PLUS.precedence
    }
}

// ---------------------------------------------------------------------------
// strip.go

/** Removes all comments. */
class StripComments : AstPass() {
    override fun fodder(fodder: Fodder, ctx: Any?) {
        fodder.elements = fodder.elements
            .filter { it.kind == FodderKind.LINE_END }
            .mapTo(mutableListOf()) { FodderElement(it.kind, it.blanks, it.indent, mutableListOf()) }
    }
}

/** Removes all comments and newlines. */
class StripEverything : AstPass() {
    override fun fodder(fodder: Fodder, ctx: Any?) {
        fodder.elements = mutableListOf()
    }
}

/** Removes everything but comments, which are gathered at the top of the file. */
class StripAllButComments : AstPass() {
    private val comments = Fodder()

    override fun fodder(fodder: Fodder, ctx: Any?) {
        for (el in fodder.elements) {
            if (el.kind == FodderKind.PARAGRAPH) {
                comments.elements.add(FodderElement(FodderKind.PARAGRAPH, 0, 0, el.comment))
            } else if (el.kind == FodderKind.INTERSTITIAL) {
                comments.elements.add(el)
                comments.elements.add(FodderElement(FodderKind.LINE_END, 0, 0, mutableListOf()))
            }
        }
        fodder.elements = mutableListOf()
    }

    override fun file(node: Node, finalFodder: Fodder): Node {
        super.file(node, finalFodder)
        finalFodder.elements = mutableListOf()
        return LiteralNull(comments)
    }
}

// ---------------------------------------------------------------------------
// pretty_field_names.go

/** Forces minimal syntax with field lookups and definitions. */
class PrettyFieldNames : AstPass() {
    override fun onIndex(node: Index, ctx: Any?) {
        val lit = node.index
        // Maybe we can use an id instead.
        if (lit is LiteralString && isValidIdentifier(lit.value)) {
            node.index = null
            node.id = lit.value
            node.rightBracketFodder = lit.fodder
        }
        super.onIndex(node, ctx)
    }

    override fun objectField(field: ObjectField, ctx: Any?) {
        if (field.kind == ObjectFieldKind.EXPR) {
            // First try ["foo"] -> "foo".
            val lit = field.expr1
            if (lit is LiteralString) {
                field.kind = ObjectFieldKind.STR
                fodderMoveFront(lit.fodder, field.fodder1)
                val method = field.method
                if (method != null) {
                    fodderMoveFront(method.parenLeftFodder, field.fodder2)
                } else {
                    fodderMoveFront(field.opFodder, field.fodder2)
                }
            }
        }
        if (field.kind == ObjectFieldKind.STR) {
            // Then try "foo" -> foo.
            val lit = field.expr1
            if (lit is LiteralString && isValidIdentifier(lit.value)) {
                field.kind = ObjectFieldKind.ID
                field.id = lit.value
                field.fodder1 = lit.fodder
                field.expr1 = null
            }
        }
        super.objectField(field, ctx)
    }
}

// ---------------------------------------------------------------------------
// enforce_string_style.go

/** Manages string literal quoting. */
class EnforceStringStyle(private val options: Options) : AstPass() {
    override fun onLiteralString(node: LiteralString, ctx: Any?) {
        when (node.kind) {
            LiteralStringKind.BLOCK, LiteralStringKind.VERBATIM_DOUBLE, LiteralStringKind.VERBATIM_SINGLE -> return
            else -> {}
        }
        val canonical = unescapeJsonnetString(node.value)
        var numSingle = 0
        var numDouble = 0
        for (c in canonical) {
            if (c == '\'') numSingle++
            if (c == '"') numDouble++
        }
        if (numSingle > 0 && numDouble > 0) return // Don't change it.
        var useSingle = options.stringStyle == StringStyle.SINGLE
        if (numSingle > 0) useSingle = false
        if (numDouble > 0) useSingle = true
        // Change it.
        node.value = escapeJsonnetString(canonical, useSingle)
        node.kind = if (useSingle) LiteralStringKind.SINGLE else LiteralStringKind.DOUBLE
    }
}

// ---------------------------------------------------------------------------
// enforce_comment_style.go

/** Ensures comments are styled according to [Options.commentStyle]. */
class EnforceCommentStyle(private val options: Options) : AstPass() {
    private var seenFirstFodder = false

    override fun fodderElement(element: FodderElement, ctx: Any?) {
        if (element.kind != FodderKind.INTERSTITIAL) {
            if (element.comment.size == 1) {
                val comment = element.comment[0]
                if (options.commentStyle == CommentStyle.HASH && comment[0] == '/') {
                    element.comment[0] = "#" + comment.substring(2)
                }
                if (options.commentStyle == CommentStyle.SLASH && comment[0] == '#') {
                    if (!seenFirstFodder && comment.length > 1 && comment[1] == '!') return
                    element.comment[0] = "//" + comment.substring(1)
                }
            }
            seenFirstFodder = true
        }
    }
}
