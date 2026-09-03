package com.dz.intellijjsonnet.formatter

import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock

/**
 * Native (non-shell-out) formatter, v1: gets indentation right for object and
 * array literals — the highest-visual-impact part of Reformat Code for a
 * JSON-like language — plus baseline operator/punctuation spacing. Doesn't
 * attempt line-wrapping decisions or comment/text-block-aware reflow; the
 * Phase 2 `jsonnetfmt`/`tk fmt` shell-out stays registered alongside this as
 * a fallback for anyone who wants exact upstream-`jsonnetfmt` output.
 */
class JsonnetBlock(
    node: ASTNode,
    wrap: Wrap?,
    alignment: Alignment?,
    private val spacingBuilder: SpacingBuilder,
) : AbstractBlock(node, wrap, alignment) {

    companion object {
        private val INDENTED_CONTAINERS = setOf(
            JsonnetTypes.OBJECT_LITERAL,
            JsonnetTypes.ARRAY_LITERAL,
            JsonnetTypes.OBJECT_MEMBER_LIST,
            JsonnetTypes.ARRAY_MEMBER_LIST,
            JsonnetTypes.ARG_LIST,
            JsonnetTypes.PARAM_LIST,
        )
    }

    override fun buildChildren(): MutableList<Block> {
        val blocks = mutableListOf<Block>()
        var child = myNode.firstChildNode
        while (child != null) {
            if (child.elementType != TokenType.WHITE_SPACE && child.textRange.length > 0) {
                blocks.add(JsonnetBlock(child, null, null, spacingBuilder))
            }
            child = child.treeNext
        }
        return blocks
    }

    override fun getIndent(): Indent? {
        val parentType = myNode.treeParent?.elementType ?: return Indent.getNoneIndent()
        if (parentType !in INDENTED_CONTAINERS) return Indent.getNoneIndent()
        // The closing bracket/brace itself (and the container's own opening one,
        // which is this same node when parentType matches on the literal itself)
        // sits back at the container's own indent level, not one level deeper.
        return when (myNode.elementType) {
            JsonnetTypes.RBRACE, JsonnetTypes.RBRACK -> Indent.getNoneIndent()
            else -> Indent.getNormalIndent()
        }
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val type = myNode.elementType
        return if (type in INDENTED_CONTAINERS) {
            ChildAttributes(Indent.getNormalIndent(), null)
        } else {
            ChildAttributes(Indent.getNoneIndent(), null)
        }
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? = spacingBuilder.getSpacing(this, child1, child2)

    override fun isLeaf(): Boolean = myNode.firstChildNode == null
}

fun jsonnetSpacingBuilder(settings: com.intellij.psi.codeStyle.CodeStyleSettings): SpacingBuilder =
    SpacingBuilder(settings, com.dz.intellijjsonnet.lang.JsonnetLanguage)
        .before(JsonnetTypes.COLON).spaces(0)
        .after(JsonnetTypes.COLON).spaces(1)
        .before(JsonnetTypes.COLONCOLON).spaces(0)
        .after(JsonnetTypes.COLONCOLON).spaces(1)
        .before(JsonnetTypes.PLUSCOLON).spaces(0)
        .after(JsonnetTypes.PLUSCOLON).spaces(1)
        .before(JsonnetTypes.COMMA).spaces(0)
        .after(JsonnetTypes.COMMA).spaces(1)
        .around(JsonnetTypes.ASSIGN).spaces(1)
        .around(JsonnetTypes.OROR).spaces(1)
        .around(JsonnetTypes.ANDAND).spaces(1)
        .around(JsonnetTypes.EQEQ).spaces(1)
        .around(JsonnetTypes.NEQ).spaces(1)
        .around(JsonnetTypes.LTE).spaces(1)
        .around(JsonnetTypes.GTE).spaces(1)
        .around(JsonnetTypes.PLUS).spaces(1)
        .around(JsonnetTypes.MINUS).spaces(1)
        .around(JsonnetTypes.STAR).spaces(1)
        .around(JsonnetTypes.SLASH).spaces(1)
        .around(JsonnetTypes.PERCENT).spaces(1)
        .before(JsonnetTypes.LPAREN).spaces(0)
        .before(JsonnetTypes.LBRACK).spaces(0)
        .before(JsonnetTypes.DOT).spaces(0)
        .after(JsonnetTypes.DOT).spaces(0)
