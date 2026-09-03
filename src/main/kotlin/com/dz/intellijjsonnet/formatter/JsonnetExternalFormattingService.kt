package com.dz.intellijjsonnet.formatter

import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.psi.PsiFile
import com.intellij.util.EnvironmentUtil
import java.io.File

/**
 * Formatter v1 (per the plan): shell out to `jsonnetfmt` (or `tk fmt`, its
 * Tanka-flavored wrapper) if either is on PATH, wired into Reformat Code as a
 * pragmatic stopgap — replaced by a native PSI-based formatter in Phase 4.
 */
class JsonnetExternalFormattingService : AsyncDocumentFormattingService() {

    override fun getFeatures(): MutableSet<FormattingService.Feature> = mutableSetOf()

    override fun canFormat(file: PsiFile): Boolean =
        file.language == JsonnetLanguage && findFormatterCommand() != null

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val command = findFormatterCommand() ?: return null
        return object : FormattingTask {
            @Volatile
            private var process: com.intellij.execution.process.ProcessHandler? = null

            override fun run() {
                try {
                    val commandLine = GeneralCommandLine(command).withParameters("-")
                    val handler = CapturingProcessHandler(commandLine)
                    process = handler
                    handler.processInput.write(request.documentText.toByteArray(Charsets.UTF_8))
                    handler.processInput.close()
                    val output = handler.runProcess(10_000)
                    if (output.exitCode == 0 && !output.isTimeout) {
                        request.onTextReady(output.stdout)
                    } else {
                        request.onError("Jsonnet formatter", output.stderr.ifBlank { "exit code ${output.exitCode}" })
                    }
                } catch (e: Exception) {
                    request.onError("Jsonnet formatter", e.message ?: e.toString())
                }
            }

            override fun cancel(): Boolean {
                process?.destroyProcess()
                return true
            }

            override fun isRunUnderProgress(): Boolean = true
        }
    }

    override fun getNotificationGroupId(): String = "Jsonnet"

    override fun getName(): String = "Jsonnet formatter (jsonnetfmt/tk fmt)"

    private fun findFormatterCommand(): List<String>? {
        onPath("jsonnetfmt")?.let { return listOf(it) }
        onPath("tk")?.let { return listOf(it, "fmt") }
        return null
    }

    private fun onPath(executable: String): String? {
        val path = EnvironmentUtil.getValue("PATH") ?: return null
        for (dir in path.split(File.pathSeparatorChar)) {
            val candidate = File(dir, executable)
            if (candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }
}
