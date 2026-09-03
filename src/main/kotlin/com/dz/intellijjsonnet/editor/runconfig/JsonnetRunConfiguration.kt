package com.dz.intellijjsonnet.editor.runconfig

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element

/** Which ground-truth-tier command this configuration runs — see plan §4.2. */
enum class JsonnetRunCommand(val label: String) {
    JSONNET_EVAL("jsonnet eval"),
    TK_SHOW("tk show"),
    TK_DIFF("tk diff"),
    TK_APPLY("tk apply"),
    TK_EXPORT("tk export"),
}

class JsonnetRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    var command: JsonnetRunCommand = JsonnetRunCommand.JSONNET_EVAL
    var targetPath: String = ""
    var extraArgs: String = ""

    override fun getConfigurationEditor(): SettingsEditor<JsonnetRunConfiguration> = JsonnetRunConfigurationEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        RunProfileState { _, _ ->
            val commandLine = buildCommandLine()
            val handler = OSProcessHandler(commandLine)
            com.intellij.execution.DefaultExecutionResult(
                com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance()
                    .createBuilder(project)
                    .console
                    .also { it.attachToProcess(handler) },
                handler,
            )
        }

    private fun buildCommandLine(): GeneralCommandLine {
        val args = extraArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
        val commandLine = when (command) {
            JsonnetRunCommand.JSONNET_EVAL -> GeneralCommandLine("jsonnet").withParameters(targetPath)
            JsonnetRunCommand.TK_SHOW -> GeneralCommandLine("tk", "show", targetPath)
            JsonnetRunCommand.TK_DIFF -> GeneralCommandLine("tk", "diff", targetPath)
            JsonnetRunCommand.TK_APPLY -> GeneralCommandLine("tk", "apply", targetPath)
            JsonnetRunCommand.TK_EXPORT -> GeneralCommandLine("tk", "export", "manifests", targetPath)
        }
        return commandLine.withParameters(args)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("jsonnetCommand", command.name)
        element.setAttribute("jsonnetTargetPath", targetPath)
        element.setAttribute("jsonnetExtraArgs", extraArgs)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        element.getAttributeValue("jsonnetCommand")?.let {
            command = runCatching { JsonnetRunCommand.valueOf(it) }.getOrDefault(JsonnetRunCommand.JSONNET_EVAL)
        }
        targetPath = element.getAttributeValue("jsonnetTargetPath") ?: ""
        extraArgs = element.getAttributeValue("jsonnetExtraArgs") ?: ""
    }
}
