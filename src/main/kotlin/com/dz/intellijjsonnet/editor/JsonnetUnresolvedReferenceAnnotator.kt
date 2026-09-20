package com.dz.intellijjsonnet.editor

import com.dz.intellijjsonnet.engine.SjsonnetStaticCheck
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetLocalReference
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Flags a `local`/param/comprehension-variable identifier that doesn't resolve
 * to anything — the "unresolved-symbol diagnostics" part of the plan's Phase 2
 * item. Primary source is still [com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver]'s
 * own lexical-scope walk (fast, one check per identifier, no reparse) — but every
 * element is also cross-checked against [SjsonnetStaticCheck], which runs the *real*
 * sjsonnet name-resolution pass sjsonnet itself runs before every evaluation. If
 * sjsonnet finds an unresolved name our own resolver missed (a false negative —
 * exactly the "hand-rolled resolver can silently diverge from real Jsonnet scoping
 * rules" trust gap TODO.md's item 3 was about), that identifier gets flagged too,
 * even though our own quick check thought it was fine. See [SjsonnetStaticCheck]'s
 * doc for why this is additive rather than a full replacement: the real optimizer's
 * unresolved-name check is fail-fast (one divergence per file per pass), so it can't
 * stand in as the sole, complete source of "every unresolved identifier in this file"
 * the way the per-element PSI walk can.
 */
class JsonnetUnresolvedReferenceAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is JsonnetNameRef) return
        val reference = element.reference as? JsonnetLocalReference ?: return
        if (reference.isSoft) return

        val ownResolveFailed = reference.resolve() == null
        val sjsonnetDivergence = sjsonnetDivergence(element.containingFile)
        val flaggedBySjsonnetOnly = !ownResolveFailed &&
            sjsonnetDivergence != null &&
            element.textRange.contains(sjsonnetDivergence.offset)

        if (!ownResolveFailed && !flaggedBySjsonnetOnly) return

        val message = if (flaggedBySjsonnetOnly) sjsonnetDivergence!!.message else "Unresolved reference: '${element.text}'"
        holder.newAnnotation(HighlightSeverity.ERROR, message)
            .range(element.textRange)
            .create()
    }

    /**
     * Cached per file (invalidated on any PSI change) — the underlying check
     * reparses the whole file through sjsonnet's own parser, too expensive to
     * redo for every single identifier in the file on every annotation pass.
     */
    private fun sjsonnetDivergence(file: PsiFile): SjsonnetStaticCheck.UnresolvedVariable? =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                SjsonnetStaticCheck.firstUnresolvedVariable(file.name, file.text),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }
}
