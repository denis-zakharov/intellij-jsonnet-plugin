package io.github.denis_zakharov.jsonnettanka.formatter

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage

/** Registers [JsonnetCodeStyleSettings] and the Settings | Editor | Code Style | Jsonnet page. */
class JsonnetCodeStyleSettingsProvider : CodeStyleSettingsProvider() {
    override fun createCustomSettings(settings: CodeStyleSettings): CustomCodeStyleSettings = JsonnetCodeStyleSettings(settings)

    override fun getLanguage() = JsonnetLanguage

    override fun getConfigurableDisplayName(): String = "Jsonnet"

    override fun createConfigurable(settings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(settings, modelSettings, configurableDisplayName) {
            override fun createPanel(settings: CodeStyleSettings) = JsonnetCodeStyleMainPanel(currentSettings, settings)
        }

    private class JsonnetCodeStyleMainPanel(currentSettings: CodeStyleSettings, settings: CodeStyleSettings) :
        TabbedLanguageCodeStylePanel(JsonnetLanguage, currentSettings, settings)
}
