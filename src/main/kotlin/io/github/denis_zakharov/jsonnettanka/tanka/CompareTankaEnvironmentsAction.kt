package io.github.denis_zakharov.jsonnettanka.tanka

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile

/**
 * Phase 5's "cross-environment diff: source-level `tk diff`-style comparison
 * between two environments' evaluated output without needing a live
 * cluster" item. Ground-truth tier by nature (§4.2) — shells out to the
 * user's own `tk show` for each environment (the same command the Phase 4
 * run configuration and gutter icon already use) and opens the result in
 * IntelliJ's own diff viewer, rather than talking to a real cluster the way
 * `tk diff` itself does.
 */
class CompareTankaEnvironmentsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = currentEnvironment(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val current = currentEnvironment(e) ?: return
        val others = TankaEnvironments.findAll(project).filterValues { it.path != current.path }
        if (others.isEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup("Jsonnet")
                .createNotification("No other Tanka environments found to compare against", NotificationType.WARNING)
                .notify(project)
            return
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(others.keys.sorted())
            .setTitle("Compare '${current.name}' With")
            .setItemChosenCallback { name -> others[name]?.let { runDiff(project, current, it) } }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    private fun currentEnvironment(e: AnActionEvent): VirtualFile? {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return TankaEnvironments.environmentOf(file)
    }

    private fun runDiff(project: Project, envA: VirtualFile, envB: VirtualFile) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Running 'tk show' for both environments", true) {
                override fun run(indicator: ProgressIndicator) {
                    val outputA = tkShow(envA.path)
                    val outputB = tkShow(envB.path)
                    ApplicationManager.getApplication().invokeLater {
                        if (outputA == null || outputB == null) {
                            NotificationGroupManager.getInstance().getNotificationGroup("Jsonnet")
                                .createNotification(
                                    "'tk show' failed for '${if (outputA == null) envA.name else envB.name}'",
                                    NotificationType.ERROR,
                                )
                                .notify(project)
                            return@invokeLater
                        }
                        val factory = DiffContentFactory.getInstance()
                        val request = SimpleDiffRequest(
                            "tk show: ${envA.name} vs ${envB.name}",
                            factory.create(project, outputA, PlainTextFileType.INSTANCE),
                            factory.create(project, outputB, PlainTextFileType.INSTANCE),
                            envA.name,
                            envB.name,
                        )
                        DiffManager.getInstance().showDiff(project, request)
                    }
                }
            },
        )
    }

    /** `null` on failure/timeout — the two-tier plan never silently substitutes a fast-tier approximation for `tk show`'s real output. */
    private fun tkShow(envPath: String): String? {
        val commandLine = GeneralCommandLine(listOf("tk", "show", envPath))
        val output = CapturingProcessHandler(commandLine).runProcess(60_000)
        return if (output.exitCode == 0 && !output.isTimeout) output.stdout else null
    }
}
