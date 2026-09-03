package com.dz.intellijjsonnet.editor.runconfig

import com.dz.intellijjsonnet.lang.JsonnetIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

/** `jsonnet eval` / `tk show|diff|apply|export` — the ground-truth tier (plan §4.2), shelling out to the user's own binaries. */
class JsonnetRunConfigurationType : ConfigurationTypeBase(
    "JsonnetRunConfigurationType",
    "Jsonnet/Tanka",
    "Run jsonnet eval or a tk command against a file or environment",
    JsonnetIcons.FILE,
) {
    init {
        addFactory(JsonnetRunConfigurationFactory(this))
    }
}

class JsonnetRunConfigurationFactory(type: JsonnetRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "JsonnetRunConfigurationFactory"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        JsonnetRunConfiguration(project, this, "Jsonnet/Tanka")
}
