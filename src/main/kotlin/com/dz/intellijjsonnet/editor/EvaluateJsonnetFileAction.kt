package com.dz.intellijjsonnet.editor

import com.dz.intellijjsonnet.engine.JsonnetEngine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages

/**
 * Phase 0 smoke-test action: evaluates the current Jsonnet file with the embedded,
 * shaded `sjsonnet` interpreter and shows the resulting JSON (or error) in a dialog.
 * Confirms there are no classloader conflicts end-to-end inside a real IDE action.
 */
class EvaluateJsonnetFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.PSI_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val result = JsonnetEngine.evaluate(file.name, file.text)
        val message = when (result) {
            is JsonnetEngine.Result.Success -> result.json
            is JsonnetEngine.Result.Failure -> "Evaluation failed:\n${result.message}"
        }
        Messages.showInfoMessage(e.project, message, "Jsonnet Evaluation Result")
    }
}
