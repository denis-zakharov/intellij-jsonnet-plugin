package com.dz.intellijjsonnet.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Opens the Jsonnet Preview tool window (§ Phase 2), which does the actual
 * evaluation — with real import resolution and ext-var support, unlike this
 * action's Phase 0 incarnation, which only ever evaluated an importless
 * in-memory snippet via a one-shot dialog.
 */
class EvaluateJsonnetFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.PSI_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Jsonnet Preview")?.activate(null)
    }
}
