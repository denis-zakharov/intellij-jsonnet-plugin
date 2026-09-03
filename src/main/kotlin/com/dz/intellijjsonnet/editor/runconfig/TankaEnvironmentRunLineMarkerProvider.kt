package com.dz.intellijjsonnet.editor.runconfig

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiElement
import java.util.function.Function

/** Gutter icon on an environment's `main.jsonnet` running `tk show` directly — the ground-truth tier (plan §4.2). */
class TankaEnvironmentRunLineMarkerProvider : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.textOffset != 0) return null
        val virtualFile = element.containingFile?.virtualFile ?: return null
        if (virtualFile.name != "main.jsonnet") return null
        val envDir = virtualFile.parent ?: return null
        if (envDir.parent?.name != "environments") return null

        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            arrayOf(RunTkShowAction(envDir.path)),
            Function { "Run 'tk show'" },
        )
    }
}

private class RunTkShowAction(private val envDirPath: String) : AnAction("Run 'tk show'") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commandLine = GeneralCommandLine("tk", "show", envDirPath)
        val handler = OSProcessHandler(commandLine)
        RunContentExecutor(project, handler).withTitle("tk show: $envDirPath").run()
    }
}
