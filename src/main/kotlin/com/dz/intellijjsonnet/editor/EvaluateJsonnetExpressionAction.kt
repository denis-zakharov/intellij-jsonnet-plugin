package com.dz.intellijjsonnet.editor

import com.dz.intellijjsonnet.engine.JsonnetEngine
import com.dz.intellijjsonnet.lang.JsonnetFileType
import com.dz.intellijjsonnet.lang.LibsonnetFileType
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetLocalExpr
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Phase 5's "Evaluate expression" item, scoped down from the plan's stretch
 * framing ("lightweight debugger hooks ... breakpoints on object fields,
 * step-through of lazy thunks"): `sjsonnet`'s public surface has no hooks for
 * that (see AGENTS.md's native-function-registration finding — verified via
 * `javap`, not assumed), so a real breakpoint/step debugger isn't attempted.
 * What *is* buildable on the existing fast tier: evaluate the current
 * selection as a standalone expression, prefixed with the file's own leading
 * `local` chain (verbatim source text, so nested/chained locals just work) so
 * references to top-level locals resolve. Anything the selection references
 * beyond that — `self`/`super`, params, imports relative to a different
 * scope — isn't in context, same as pasting the selection into a REPL
 * wouldn't be either; this is a convenience for self-contained sub-
 * expressions, not full in-context evaluation.
 */
class EvaluateJsonnetExpressionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val isJsonnet = file?.fileType == JsonnetFileType || file?.fileType == LibsonnetFileType
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = isJsonnet && hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val selection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.trim().orEmpty()
        if (selection.isEmpty()) return

        val source = leadingLocalsPrefix(file) + selection
        when (val result = JsonnetEngine.evaluate(file.name, source)) {
            is JsonnetEngine.Result.Success -> Messages.showInfoMessage(project, result.json, "Jsonnet Evaluation Result")
            is JsonnetEngine.Result.Failure -> Messages.showErrorDialog(project, result.message, "Jsonnet Evaluation Failed")
        }
    }

    /** Verbatim source text of every leading `local ...;` in [file], up to (not including) the final value expression. */
    private fun leadingLocalsPrefix(file: PsiFile): String {
        var expr = PsiTreeUtil.findChildOfType(file, JsonnetExpr::class.java) ?: return ""
        while (true) {
            val local = PsiTreeUtil.getChildOfType(expr, JsonnetLocalExpr::class.java) ?: break
            expr = local.expr ?: break
        }
        return file.text.substring(0, expr.textRange.startOffset)
    }
}
