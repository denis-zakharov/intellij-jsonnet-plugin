package com.dz.intellijjsonnet.editor

import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetLocalReference
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity

/**
 * Flags a `local`/param/comprehension-variable identifier that doesn't resolve
 * to anything — the "unresolved-symbol diagnostics" part of the plan's Phase 2
 * item. Sourced from [com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver]'s
 * own lexical-scope walk (Phase 1), not sjsonnet's `StaticOptimizer`; wiring the
 * latter in for full parity with sjsonnet's own scope resolution is a follow-up.
 */
class JsonnetUnresolvedReferenceAnnotator : Annotator {
    override fun annotate(element: com.intellij.psi.PsiElement, holder: AnnotationHolder) {
        if (element !is JsonnetNameRef) return
        val reference = element.reference as? JsonnetLocalReference ?: return
        if (reference.isSoft) return
        if (reference.resolve() != null) return
        holder.newAnnotation(HighlightSeverity.ERROR, "Unresolved reference: '${element.text}'")
            .range(element.textRange)
            .create()
    }
}
