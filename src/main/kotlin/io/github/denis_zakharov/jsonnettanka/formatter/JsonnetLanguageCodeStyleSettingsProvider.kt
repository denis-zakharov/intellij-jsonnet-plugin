package io.github.denis_zakharov.jsonnettanka.formatter

import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizableOptions
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage

/**
 * Which code style options the Jsonnet page shows, their defaults, and a live preview. `jsonnetfmt` only indents with
 * spaces, so tabs and continuation indent are not offered.
 */
class JsonnetLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = JsonnetLanguage

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.TAB_SIZE = 2
        indentOptions.USE_TAB_CHARACTER = false
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        val options = CodeStyleSettingsCustomizableOptions.getInstance()
        val cls = JsonnetCodeStyleSettings::class.java
        when (settingsType) {
            SettingsType.INDENT_SETTINGS -> consumer.showStandardOptions("INDENT_SIZE")
            SettingsType.SPACING_SETTINGS -> {
                consumer.showCustomOption(cls, "PAD_ARRAYS", "Array brackets: [ a ]", options.SPACES_WITHIN)
                consumer.showCustomOption(cls, "PAD_OBJECTS", "Object braces: { a }", options.SPACES_WITHIN)
            }
            SettingsType.BLANK_LINES_SETTINGS ->
                consumer.showCustomOption(cls, "MAX_BLANK_LINES", "Maximum blank lines (0 = no limit)", options.BLANK_LINES_KEEP)
            SettingsType.LANGUAGE_SPECIFIC -> {
                val group = "Rewrites (jsonnetfmt)"
                consumer.showCustomOption(
                    cls, "STRING_STYLE", "String quotes", group,
                    JsonnetCodeStyleSettings.STRING_STYLE_OPTIONS, JsonnetCodeStyleSettings.STRING_STYLE_VALUES,
                )
                consumer.showCustomOption(
                    cls, "COMMENT_STYLE", "Line comments", group,
                    JsonnetCodeStyleSettings.COMMENT_STYLE_OPTIONS, JsonnetCodeStyleSettings.COMMENT_STYLE_VALUES,
                )
                consumer.showCustomOption(cls, "PRETTY_FIELD_NAMES", "Quote field names only when needed", group)
                consumer.showCustomOption(cls, "SORT_IMPORTS", "Sort imports at the top of the file", group)
                consumer.showCustomOption(cls, "USE_IMPLICIT_PLUS", "Drop redundant '+' before an object", group)
            }
            else -> {}
        }
    }

    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE

    private companion object {
        val SAMPLE = """
            local k = import 'k.libsonnet';
            local deployment = k.apps.v1.deployment;

            {
              "name": "example",   # trailing comment
              spec+: { replicas: 3, args: ['--flag', "--other"] },
              deployment: deployment.new(name='web', replicas=2,
                                          containers=[]),
              hidden:: [1, 2, 3],
            }
        """.trimIndent() + "\n"
    }
}
