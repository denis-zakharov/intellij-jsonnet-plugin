package com.dz.intellijjsonnet.inspection

import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetLocalExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetObjectLocal
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Phase 5's "dead-code detection (unused `local`s/fields) with a remove quick
 * fix" item. Detection logic lives in [JsonnetUnusedDeclarationUtil] (unit
 * tested via `ParsingTestCase`, see [com.dz.intellijjsonnet.inspection] test
 * sources) — this class is just the `LocalInspectionTool`/quick-fix wiring,
 * which (like the rename/formatter write paths from Phase 4) needs a real
 * `PsiFileFactory`/document-commit service to exercise end-to-end, so it's
 * reviewed but not automated-tested in this environment.
 */
class JsonnetUnusedDeclarationInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when {
                    element is JsonnetBind && JsonnetUnusedDeclarationUtil.isUnused(element) -> {
                        val name = element.nameIdentifier ?: return
                        holder.registerProblem(name, "Local '${name.text}' is never used", RemoveBindFix())
                    }
                    element is JsonnetField && JsonnetUnusedDeclarationUtil.isUnusedHiddenField(element) -> {
                        val name = element.nameIdentifier
                        val target = name ?: element
                        val label = name?.text ?: JsonnetResolver.fieldNameText(element) ?: return
                        holder.registerProblem(target, "Hidden field '$label' is never used", RemoveFieldFix())
                    }
                }
            }
        }
}

private class RemoveBindFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unused local"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val bind = descriptor.psiElement.parent as? JsonnetBind ?: return
        when (val parent = bind.parent) {
            is JsonnetLocalExpr -> {
                if (parent.bindList.size == 1) {
                    val outerExpr = parent.parent as? JsonnetExpr ?: return
                    val body = parent.expr ?: return
                    outerExpr.replace(body)
                } else {
                    JsonnetPsiListEditUtil.deleteListMember(bind)
                }
            }
            is JsonnetObjectLocal -> JsonnetPsiListEditUtil.deleteListMember(parent)
            else -> {}
        }
    }
}

private class RemoveFieldFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unused hidden field"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val target = descriptor.psiElement
        val field = target as? JsonnetField ?: target.parent as? JsonnetField ?: return
        JsonnetPsiListEditUtil.deleteListMember(field)
    }
}
