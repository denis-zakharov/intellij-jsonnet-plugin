package com.dz.intellijjsonnet.editor.runconfig

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class JsonnetRunConfigurationEditor : SettingsEditor<JsonnetRunConfiguration>() {
    private val commandBox = JComboBox(JsonnetRunCommand.entries.toTypedArray())
    private val targetField = TextFieldWithBrowseButton().apply {
        val descriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
        addActionListener {
            FileChooser.chooseFile(descriptor, null, null) { file -> text = file.path }
        }
    }
    private val extraArgsField = JBTextField()

    override fun resetEditorFrom(configuration: JsonnetRunConfiguration) {
        commandBox.selectedItem = configuration.command
        targetField.text = configuration.targetPath
        extraArgsField.text = configuration.extraArgs
    }

    override fun applyEditorTo(configuration: JsonnetRunConfiguration) {
        configuration.command = commandBox.selectedItem as JsonnetRunCommand
        configuration.targetPath = targetField.text
        configuration.extraArgs = extraArgsField.text
    }

    override fun createEditor(): JComponent {
        val panel: JPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Command", commandBox)
            .addLabeledComponent("Target (environment dir / .jsonnet file)", targetField)
            .addLabeledComponent("Extra arguments", extraArgsField)
            .panel
        return panel
    }
}
