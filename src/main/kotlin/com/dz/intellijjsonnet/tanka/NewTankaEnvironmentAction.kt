package com.dz.intellijjsonnet.tanka

import com.intellij.ide.IdeView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDirectory

/** Scaffolds `environments/<name>/{main.jsonnet,spec.json}` — the two files every Tanka environment needs. */
class NewTankaEnvironmentAction : AnAction("Tanka Environment") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(LangDataKeys.IDE_VIEW)?.directories?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val view = e.getData(LangDataKeys.IDE_VIEW) ?: return
        val parent = view.directories.firstOrNull() ?: return
        val name = Messages.showInputDialog(
            project,
            "Environment name:",
            "New Tanka Environment",
            null,
        )?.trim()
        if (name.isNullOrEmpty()) return

        WriteCommandAction.runWriteCommandAction(project, "Create Tanka Environment", null, {
            val envDir = findOrCreateSubdirectory(findOrCreateSubdirectory(parent, "environments"), name)
            if (envDir.findFile("main.jsonnet") == null) {
                envDir.createFile("main.jsonnet").also { file ->
                    file.viewProvider.document?.setText(
                        "local tk = import 'tk';\n\n{\n  // add your Kubernetes objects here\n}\n",
                    )
                }
            }
            if (envDir.findFile("spec.json") == null) {
                envDir.createFile("spec.json").also { file ->
                    file.viewProvider.document?.setText(specJsonTemplate(name))
                }
            }
        })
    }

    private fun findOrCreateSubdirectory(parent: PsiDirectory, name: String): PsiDirectory =
        parent.findSubdirectory(name) ?: parent.createSubdirectory(name)

    private fun specJsonTemplate(name: String): String = """
        {
          "apiVersion": "tanka.dev/v1alpha1",
          "kind": "Environment",
          "metadata": {
            "name": "$name"
          },
          "spec": {
            "apiServer": "",
            "namespace": "default"
          }
        }
    """.trimIndent() + "\n"
}
