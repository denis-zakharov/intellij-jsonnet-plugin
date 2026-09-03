package com.dz.intellijjsonnet.tanka

import com.intellij.codeInsight.daemon.QuickFixActionRegistrar
import com.intellij.codeInsight.quickfix.UnresolvedReferenceQuickFixProvider
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import java.io.File

/**
 * Offers "Run jb install" on an unresolved `vendor/...` import — the common
 * case where a dependency listed in `jsonnetfile.json` hasn't actually been
 * fetched yet. Ground-truth tier by nature: it shells out to the user's own
 * `jb` binary (§4.2), same as `tk show`/`tk apply` will in Phase 4.
 */
class JbInstallQuickFixProvider : UnresolvedReferenceQuickFixProvider<FileReference>() {
    override fun registerFixes(reference: FileReference, registrar: QuickFixActionRegistrar) {
        val file = reference.element.containingFile ?: return
        val root = TankaJpath.findRoot(file.virtualFile?.parent ?: return) ?: return
        if (onPath("jb") == null) return
        registrar.register(RunJbInstallFix(root.path))
    }

    override fun getReferenceClass(): Class<FileReference> = FileReference::class.java

    private fun onPath(executable: String): String? {
        val path = EnvironmentUtil.getValue("PATH") ?: return null
        for (dir in path.split(File.pathSeparatorChar)) {
            val candidate = File(dir, executable)
            if (candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }
}

private class RunJbInstallFix(private val rootPath: String) : IntentionAction {
    override fun getText(): String = "Run 'jb install'"
    override fun getFamilyName(): String = "Jsonnet"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Running jb install", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    val commandLine = GeneralCommandLine("jb", "install").withWorkDirectory(rootPath)
                    val output = CapturingProcessHandler(commandLine).runProcess(60_000)
                    val notifications = NotificationGroupManager.getInstance().getNotificationGroup("Jsonnet")
                    if (output.exitCode == 0 && !output.isTimeout) {
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(rootPath)?.refresh(true, true)
                        notifications.createNotification("jb install completed", NotificationType.INFORMATION)
                            .notify(project)
                    } else {
                        notifications.createNotification(
                            "jb install failed",
                            output.stderr.ifBlank { "exit code ${output.exitCode}" },
                            NotificationType.ERROR,
                        ).notify(project)
                    }
                }
            },
        )
    }
}
