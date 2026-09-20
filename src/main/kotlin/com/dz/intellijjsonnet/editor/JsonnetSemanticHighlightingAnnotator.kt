package com.dz.intellijjsonnet.editor

import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetForSpec
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.JsonnetParam
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetFieldReference
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetLocalReference
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType

/**
 * Phase 5's "semantic highlighting distinguishing locals/params/fields/std
 * calls" item. Layered on top of [JsonnetSyntaxHighlighter]'s purely
 * lexer-based coloring — this needs reference resolution (Phase 1/2's
 * [JsonnetLocalReference]/[JsonnetFieldReference]), so it has to be an
 * [Annotator], not a `SyntaxHighlighter`. Colors both declaration and usage
 * sites the same way, matching how IDEs usually highlight these categories.
 * The fallback attributes below match the current scheme's existing
 * local/parameter/field/static-method colors; [JsonnetColorSettingsPage] is
 * where a user overrides them independently.
 */
class JsonnetSemanticHighlightingAnnotator : Annotator {

    companion object {
        val LOCAL_VARIABLE: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("JSONNET_LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
        val PARAMETER: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("JSONNET_PARAMETER", DefaultLanguageHighlighterColors.PARAMETER)
        val FIELD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("JSONNET_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val STD_CALL: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey("JSONNET_STD_CALL", DefaultLanguageHighlighterColors.STATIC_METHOD)
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is JsonnetBind -> highlight(element.nameIdentifier, LOCAL_VARIABLE, holder)
            is JsonnetParam -> highlight(element.nameIdentifier, PARAMETER, holder)
            is JsonnetForSpec -> highlight(element.nameIdentifier, LOCAL_VARIABLE, holder)
            is JsonnetField -> highlight(element.nameIdentifier, FIELD, holder)
            is JsonnetNameRef -> highlightUsage(element, holder)
            is JsonnetDotSuffix -> highlightDotSuffix(element, holder)
            else -> {}
        }
    }

    private fun highlightUsage(nameRef: JsonnetNameRef, holder: AnnotationHolder) {
        val reference = nameRef.reference as? JsonnetLocalReference ?: return
        when (reference.resolve()) {
            is JsonnetParam -> highlight(nameRef, PARAMETER, holder)
            is JsonnetBind, is JsonnetForSpec -> highlight(nameRef, LOCAL_VARIABLE, holder)
            else -> {}
        }
    }

    private fun highlightDotSuffix(dotSuffix: JsonnetDotSuffix, holder: AnnotationHolder) {
        if (isStdCall(dotSuffix)) {
            highlight(dotSuffix.nameIdentifier, STD_CALL, holder)
            return
        }
        val reference = dotSuffix.reference as? JsonnetFieldReference ?: return
        if (reference.resolve() is JsonnetField) {
            highlight(dotSuffix.nameIdentifier, FIELD, holder)
        }
    }

    /** `std.foo` — the suffix directly after a bare `std` identifier in the same Expr. */
    private fun isStdCall(dotSuffix: JsonnetDotSuffix): Boolean {
        val exprParent = dotSuffix.parent ?: return false
        var prev: PsiElement? = dotSuffix.prevSibling
        while (prev != null && prev.node.elementType == TokenType.WHITE_SPACE) {
            prev = prev.prevSibling
        }
        if (prev == null || prev.parent != exprParent) return false
        return prev is JsonnetNameRef && prev.nameIdentifier?.text == "std"
    }

    private fun highlight(target: PsiElement?, key: TextAttributesKey, holder: AnnotationHolder) {
        val range = target?.textRange ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}
