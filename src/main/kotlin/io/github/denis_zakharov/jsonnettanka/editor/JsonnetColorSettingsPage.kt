package io.github.denis_zakharov.jsonnettanka.editor

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/**
 * Settings | Editor | Color Scheme | Jsonnet — lists both the lexer-level keys from
 * [JsonnetSyntaxHighlighter] and the four resolution-dependent keys from
 * [JsonnetSemanticHighlightingAnnotator] (local variable / parameter / field / `std` call), so each
 * can be customized independently of the fallback attributes they inherit by default.
 *
 * The semantic keys only show up in the preview because [getAdditionalHighlightingTagToDescriptorMap]
 * paints the `<tag>…</tag>` ranges in [DEMO_TEXT] by hand — the preview never runs the annotator.
 */
class JsonnetColorSettingsPage : ColorSettingsPage {

    companion object {
        internal val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Syntax//Keyword", JsonnetSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Syntax//String", JsonnetSyntaxHighlighter.STRING),
            AttributesDescriptor("Syntax//Number", JsonnetSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Syntax//Comment", JsonnetSyntaxHighlighter.COMMENT),
            AttributesDescriptor("Syntax//Braces", JsonnetSyntaxHighlighter.BRACES),
            AttributesDescriptor("Syntax//Brackets", JsonnetSyntaxHighlighter.BRACKETS),
            AttributesDescriptor("Syntax//Parentheses", JsonnetSyntaxHighlighter.PARENS),
            AttributesDescriptor("Syntax//Operator", JsonnetSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Syntax//Identifier", JsonnetSyntaxHighlighter.IDENTIFIER),
            AttributesDescriptor("Syntax//Bad character", JsonnetSyntaxHighlighter.BAD_CHARACTER),
            AttributesDescriptor("Semantic//Local variable", JsonnetSemanticHighlightingAnnotator.LOCAL_VARIABLE),
            AttributesDescriptor("Semantic//Parameter", JsonnetSemanticHighlightingAnnotator.PARAMETER),
            AttributesDescriptor("Semantic//Field", JsonnetSemanticHighlightingAnnotator.FIELD),
            AttributesDescriptor("Semantic//std function call", JsonnetSemanticHighlightingAnnotator.STD_CALL),
        )

        internal val TAGS: Map<String, TextAttributesKey> = mapOf(
            "local" to JsonnetSemanticHighlightingAnnotator.LOCAL_VARIABLE,
            "param" to JsonnetSemanticHighlightingAnnotator.PARAMETER,
            "field" to JsonnetSemanticHighlightingAnnotator.FIELD,
            "stdcall" to JsonnetSemanticHighlightingAnnotator.STD_CALL,
        )

        /** Must stay valid Jsonnet once the tags are stripped (checked by a test). */
        internal const val DEMO_TEXT = """// A Tanka-style environment
local <local>images</local> = { web: 'nginx:1.27' };

local <local>container</local>(<param>name</param>, <param>replicas</param>=1) = {
  <field>name</field>: <param>name</param>,
  <field>image</field>: <local>images</local>.<field>web</field>,
  <field>replicas</field>: <param>replicas</param>,
  <field>ports</field>: [80, 443],
  <field>enabled</field>: std.<stdcall>length</stdcall>(self.<field>ports</field>) > 0,
};

{
  <field>deployment</field>: <local>container</local>('web', replicas=3),
}
"""
    }

    override fun getDisplayName(): String = "Jsonnet"

    override fun getIcon(): Icon = JsonnetIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = JsonnetSyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
