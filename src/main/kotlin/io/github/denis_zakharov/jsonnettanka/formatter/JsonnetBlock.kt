package io.github.denis_zakharov.jsonnettanka.formatter

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.TokenSet
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes.*

/**
 * The Block-model formatter: what typing (Enter, a typed closer), paste, quick fixes and files with syntax errors use.
 * Reformat Code on a valid file goes through [JsonnetFormattingService] and the `jsonnetfmt` port instead.
 *
 * The indent rules follow `fmt/FixIndentation` (the port), translated to what our *flat* PSI offers (`a.b(c) + d` is one
 * `Expr`, no precedence tree) and to IntelliJ's `Indent`/`Alignment` vocabulary:
 *
 *  - members of `{ }`, `[ ]`, argument and parameter lists, `local` binds, comprehension parts and `assert` operands share
 *    an [Alignment] with a normal indent: if the first one sits on the opener's line the rest line up with it, otherwise
 *    they are one indent level in (`deriveIndent`);
 *  - closers, `then`/`else`, `;` and the body of a `local`/`assert` stay at the construct's own indent;
 *  - the operands, operators, `.x`, `(...)` and `{ ... }` suffixes of one flat expression all line up with its first
 *    operand (`alignStrong`/`align` in the port);
 *  - a value that starts on a new line (`a:⏎value`, `local x =⏎value`, `if c then⏎value`) is one level in.
 *
 * Deliberate deviations, all in rare shapes (see TODO.md item 15): the "strong indent" the port switches to when a later
 * call argument/array element starts a line, and hanging field values on the second and later fields of a `{ a: 1,`
 * object. `JsonnetTypingConsistencyTest` measures the agreement against real `jsonnetfmt` output.
 */
class JsonnetBlock private constructor(
    node: ASTNode,
    wrap: Wrap?,
    alignment: Alignment?,
    private val indent: Indent,
    private val ctx: Context,
) : AbstractBlock(node, wrap, alignment) {

    class Context(val settings: CodeStyleSettings) {
        val custom: JsonnetCodeStyleSettings = settings.getCustomSettings(JsonnetCodeStyleSettings::class.java)
        val keepBlankLines: Int get() = if (custom.MAX_BLANK_LINES <= 0) Int.MAX_VALUE else custom.MAX_BLANK_LINES
    }

    /**
     * The alignment shared by the members of this block (see the class comment); also handed out for a new line.
     * A flat expression that is itself an aligned member reuses its own alignment for its continuation lines: they line
     * up with the member's column, which is where the member sits anyway.
     */
    private val memberAlignment: Alignment = (if (node.elementType == EXPR) alignment else null) ?: Alignment.createAlignment()

    private class Spec(val indent: Indent, val alignment: Alignment? = null)

    override fun buildChildren(): MutableList<Block> {
        val kids = significantChildren()
        return kids.map { child ->
            val spec = specFor(child, kids)
            // Only single-line members are aligned. For a block that does not start a line, IntelliJ bases the indent of
            // everything below it that starts a line on the alignment column of any aligned block starting at the same
            // offset (`AbstractBlockWrapper.createAlignmentIndent`), which would turn `[{⏎  a: 1⏎}]` and
            // `local x =⏎  v;` into column-of-the-member indents instead of jsonnetfmt's base + one level.
            val alignment = spec.alignment?.takeUnless { child.textContains('\n') }
            JsonnetBlock(child, null, alignment, spec.indent, ctx) as Block
        }.toMutableList()
    }

    private fun significantChildren(): List<ASTNode> {
        val kids = mutableListOf<ASTNode>()
        var child = myNode.firstChildNode
        while (child != null) {
            if (child.elementType != TokenType.WHITE_SPACE && child.textLength > 0) kids += child
            child = child.treeNext
        }
        return kids
    }

    private val normal: Indent get() = Indent.getNormalIndent()
    private val none: Indent get() = Indent.getNoneIndent()

    /** Indent and alignment of [child] inside this block. Comments take those of whatever they precede. */
    private fun specFor(child: ASTNode, kids: List<ASTNode>): Spec {
        if (child.elementType == COMMENT) {
            val at = kids.indexOf(child)
            val next = kids.drop(at + 1).firstOrNull { it.elementType != COMMENT }
            val previous = kids.take(at).lastOrNull { it.elementType != COMMENT }
            return when {
                // A comment before the closer belongs to the contents.
                next != null && next.elementType in CLOSERS -> Spec(normal)
                next != null -> specFor(next, kids)
                previous != null -> specFor(previous, kids)
                else -> Spec(none)
            }
        }
        val t = child.elementType
        return when (myNode.elementType) {
            OBJECT_LITERAL, ARRAY_LITERAL ->
                Spec(if (t in LITERAL_STRUCTURE) none else normal)
            // A comprehension is not transparent like a member list: its parts (locals, `[name]: value`, `for`, `if`) are
            // its own children, so it carries the one level of indent and they sit at its column.
            OBJECT_MEMBER_LIST, ARRAY_MEMBER_LIST, ARG_LIST ->
                if (t == COMMA) Spec(normal) else Spec(normal, memberAlignment)
            PARAM_LIST -> when (t) {
                LPAREN, RPAREN -> Spec(none)
                COMMA -> Spec(normal)
                else -> Spec(normal, memberAlignment)
            }
            OBJECT_COMPREHENSION -> when (t) {
                OBJECT_LOCAL, COMPUTED_FIELD_NAME, FOR_SPEC, IF_SPEC -> Spec(none, memberAlignment)
                COLON -> Spec(none)
                else -> Spec(normal)
            }
            ARRAY_COMPREHENSION -> if (t == EXPR || t == FOR_SPEC || t == IF_SPEC) Spec(none, memberAlignment) else Spec(none)
            FIELD, BIND -> Spec(if (t == EXPR) normal else none)
            OBJECT_ASSERT -> if (t == EXPR || t == COLON) Spec(normal, memberAlignment) else Spec(none)
            ASSERT_EXPR -> {
                val semi = kids.indexOfFirst { it.elementType == SEMI }
                if (semi >= 0 && kids.indexOf(child) > semi) Spec(none)
                else if (t == ASSERT_KW) Spec(none)
                else Spec(normal, memberAlignment)
            }
            LOCAL_EXPR -> when (t) {
                BIND -> Spec(normal, memberAlignment)
                else -> Spec(none)
            }
            IF_EXPR, FUNCTION_EXPR -> Spec(if (t == EXPR) normal else none)
            IMPORT_EXPR, ERROR_EXPR, UNARY_OP_EXPR ->
                Spec(if (kids.firstOrNull() === child) none else normal)
            PAREN_EXPR, INDEX_SUFFIX, NAMED_ARG, PARAM, COMPUTED_FIELD_NAME, FOR_SPEC, IF_SPEC ->
                Spec(if (t == EXPR) normal else none)
            // The first operand of an aligned member is covered by the member's own alignment.
            EXPR -> when {
                kids.size <= 1 -> Spec(none)
                kids.first() === child && alignment != null -> Spec(none)
                else -> Spec(none, memberAlignment)
            }
            else -> Spec(none)
        }
    }

    override fun getIndent(): Indent = indent

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val blocks = subBlocks
        val previous = blocks.getOrNull(newChildIndex - 1) as? JsonnetBlock
        val prevType = previous?.myNode?.elementType
        fun list(indent: Indent) = ChildAttributes(indent, memberAlignment)
        fun plain(indent: Indent) = ChildAttributes(indent, null)
        return when (myNode.elementType) {
            OBJECT_MEMBER_LIST, ARRAY_MEMBER_LIST, ARG_LIST, PARAM_LIST,
            OBJECT_COMPREHENSION, ARRAY_COMPREHENSION, OBJECT_ASSERT -> list(normal)
            // After the contents of a bracket pair: line up with them, as the next member would.
            OBJECT_LITERAL, ARRAY_LITERAL, CALL_SUFFIX ->
                if (previous != null && prevType in MEMBER_LISTS) ChildAttributes(normal, previous.memberAlignment) else plain(normal)
            LOCAL_EXPR -> when (prevType) {
                SEMI -> plain(none)
                LOCAL_KW -> plain(normal)
                else -> list(normal)
            }
            ASSERT_EXPR -> if (prevType == SEMI) plain(none) else list(normal)
            // `if c then⏎`, `else⏎`: the branch is one level in; after a complete branch `then`/`else` come at the `if`.
            IF_EXPR -> if (prevType == IF_KW || prevType == THEN_KW || prevType == ELSE_KW) plain(normal) else plain(none)
            FIELD, BIND, FUNCTION_EXPR, IMPORT_EXPR, ERROR_EXPR, UNARY_OP_EXPR, NAMED_ARG, PARAM,
            PAREN_EXPR, INDEX_SUFFIX, COMPUTED_FIELD_NAME, FOR_SPEC, IF_SPEC -> plain(normal)
            // `a +⏎`: the continuation lines up with the first operand.
            EXPR -> if (blocks.size > 1) list(none) else plain(none)
            else -> plain(none)
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        val b1 = child1 as? JsonnetBlock ?: return null
        val b2 = child2 as? JsonnetBlock ?: return null
        val spaces = spacesBetween(myNode.elementType, b1.myNode.elementType, b2.myNode.elementType) ?: return null
        return Spacing.createSpacing(spaces, spaces, 0, true, ctx.keepBlankLines)
    }

    /** Number of spaces `jsonnetfmt` puts between two siblings, or null if we have no opinion (comments, gaps, ...). */
    private fun spacesBetween(parent: com.intellij.psi.tree.IElementType, t1: com.intellij.psi.tree.IElementType, t2: com.intellij.psi.tree.IElementType): Int? {
        if (t1 == COMMENT || t2 == COMMENT) return null
        fun pad(on: Boolean) = if (on) 1 else 0
        return when (parent) {
            OBJECT_LITERAL -> when {
                t1 == LBRACE && t2 == RBRACE -> 0
                t1 == LBRACE || t2 == RBRACE -> pad(ctx.custom.PAD_OBJECTS)
                else -> null
            }
            ARRAY_LITERAL -> when {
                t1 == LBRACK && t2 == RBRACK -> 0
                t1 == LBRACK || t2 == RBRACK -> pad(ctx.custom.PAD_ARRAYS)
                else -> null
            }
            OBJECT_MEMBER_LIST, ARRAY_MEMBER_LIST, ARG_LIST -> when {
                t2 == COMMA -> 0
                t1 == COMMA -> 1
                else -> null
            }
            PARAM_LIST -> when {
                t1 == LPAREN || t2 == RPAREN || t2 == COMMA -> 0
                t1 == COMMA -> 1
                else -> null
            }
            CALL_SUFFIX, INDEX_SUFFIX, PAREN_EXPR, COMPUTED_FIELD_NAME, DOT_SUFFIX -> 0
            FIELD -> when {
                t2 == PARAM_LIST || t2 in FIELD_OPS -> 0
                t1 in FIELD_OPS -> 1
                else -> null
            }
            BIND -> if (t2 == PARAM_LIST) 0 else if (t1 == ASSIGN || t2 == ASSIGN) 1 else null
            NAMED_ARG, PARAM -> if (t1 == ASSIGN || t2 == ASSIGN) 0 else null
            LOCAL_EXPR -> if (t2 == COMMA || t2 == SEMI) 0 else 1
            OBJECT_LOCAL, OBJECT_ASSERT, ARRAY_COMPREHENSION, FOR_SPEC, IF_SPEC, IF_EXPR, IMPORT_EXPR, ERROR_EXPR -> 1
            OBJECT_COMPREHENSION -> if (t2 == COMMA || t2 == COLON) 0 else 1
            FUNCTION_EXPR -> if (t2 == PARAM_LIST) 0 else 1
            ASSERT_EXPR -> if (t2 == SEMI) 0 else 1
            UNARY_OP_EXPR -> if (t1 in UNARY_OPS) 0 else flatSpacing(t1, t2)
            EXPR -> flatSpacing(t1, t2)
            else -> null
        }
    }

    private fun flatSpacing(t1: com.intellij.psi.tree.IElementType, t2: com.intellij.psi.tree.IElementType): Int? = when {
        t2 == DOT_SUFFIX || t2 == CALL_SUFFIX || t2 == INDEX_SUFFIX -> 0
        t1 in BINARY_OPS || t2 in BINARY_OPS -> 1
        t2 == OBJECT_LITERAL -> 1 // `base { ... }`, the implicit `+`
        else -> null
    }

    override fun isLeaf(): Boolean = myNode.firstChildNode == null

    companion object {
        private val LITERAL_STRUCTURE = TokenSet.create(
            LBRACE, RBRACE, LBRACK, RBRACK, OBJECT_MEMBER_LIST, ARRAY_MEMBER_LIST,
        )
        private val CLOSERS = TokenSet.create(RBRACE, RBRACK, RPAREN)
        private val MEMBER_LISTS = TokenSet.create(OBJECT_MEMBER_LIST, ARRAY_MEMBER_LIST, ARG_LIST, OBJECT_COMPREHENSION, ARRAY_COMPREHENSION)
        private val FIELD_OPS = TokenSet.create(COLON, COLONCOLON, COLONCOLONCOLON, PLUSCOLON, PLUSCOLONCOLON, PLUSCOLONCOLONCOLON)
        private val UNARY_OPS = TokenSet.create(BANG, MINUS, PLUS, TILDE)
        private val BINARY_OPS = TokenSet.create(
            OROR, ANDAND, PIPE, CARET, AMP, EQEQ, NEQ, LTE, GTE, SHL, SHR, LT, GT, IN_KW, PLUS, MINUS, STAR, SLASH, PERCENT,
        )

        fun root(node: ASTNode, settings: CodeStyleSettings): JsonnetBlock =
            JsonnetBlock(node, null, null, Indent.getNoneIndent(), Context(settings))
    }
}
