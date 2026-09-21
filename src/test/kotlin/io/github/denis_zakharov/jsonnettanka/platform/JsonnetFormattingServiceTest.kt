package io.github.denis_zakharov.jsonnettanka.platform

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.denis_zakharov.jsonnettanka.fmt.JsonnetFormatter
import io.github.denis_zakharov.jsonnettanka.formatter.JsonnetCodeStyleSettings
import io.github.denis_zakharov.jsonnettanka.formatter.JsonnetTextEdits
import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage

/**
 * Reformat Code through the real platform entry points, which must land in `JsonnetFormattingService` and produce what
 * `jsonnetfmt` produces (byte-exactness of the formatter itself is covered by `fmt/`).
 */
class JsonnetFormattingServiceTest : BasePlatformTestCase() {
    private val messy = "local k = import \"k.libsonnet\";\n{a:1,\n  \"b\" : [1,2],\n# note\n  c:  'x'}"

    private fun reformat(canChangeWhiteSpaceOnly: Boolean = false) {
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file, canChangeWhiteSpaceOnly)
        }
    }

    fun `test Reformat Code equals jsonnetfmt output`() {
        myFixture.configureByText("a.jsonnet", messy)
        reformat()
        myFixture.checkResult(JsonnetFormatter.format(messy))
        assertEquals(
            "local k = import 'k.libsonnet';\n{\n  a: 1,\n  b: [1, 2],\n  // note\n  c: 'x',\n}\n",
            myFixture.editor.document.text,
        )
    }

    fun `test the Reformat Code action goes through the service too`() {
        myFixture.configureByText("a.jsonnet", messy)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        myFixture.checkResult(JsonnetFormatter.format(messy))
    }

    fun `test libsonnet files are formatted as well`() {
        myFixture.configureByText("lib.libsonnet", "{f(x):: x+1}")
        reformat()
        myFixture.checkResult("{ f(x):: x + 1 }\n")
    }

    fun `test default indent is two spaces like jsonnetfmt`() {
        myFixture.configureByText("a.jsonnet", "{}")
        val options = CodeStyle.getSettings(myFixture.file).getIndentOptionsByFile(myFixture.file)
        assertEquals(2, options.INDENT_SIZE)
        assertFalse(options.USE_TAB_CHARACTER)
    }

    fun `test the caret survives a reformat`() {
        myFixture.configureByText("a.jsonnet", "{a:1,b:<caret>2}")
        reformat()
        myFixture.checkResult("{ a: 1, b: <caret>2 }\n")
    }

    fun `test a selection only takes the edits inside it`() {
        val text = "local a = {x:1,   y:2};\nlocal b = {x:1,   y:2};\n{a:a,b:b}\n"
        myFixture.configureByText("a.jsonnet", text)
        val firstLineEnd = text.indexOf('\n')
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(myFixture.file, 0, firstLineEnd)
        }
        myFixture.checkResult("local a = { x: 1, y: 2 };\nlocal b = {x:1,   y:2};\n{a:a,b:b}\n")
    }

    fun `test settings drive the output`() {
        // Settings first, then open the file: the platform associates indent options with a document when it is opened.
        CodeStyle.runWithLocalSettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCustomSettings(JsonnetCodeStyleSettings::class.java).apply {
                STRING_STYLE = JsonnetCodeStyleSettings.STRING_DOUBLE
                PRETTY_FIELD_NAMES = false
                PAD_ARRAYS = true
            }
            settings.getCommonSettings(JsonnetLanguage).indentOptions!!.INDENT_SIZE = 4
            myFixture.configureByText("a.jsonnet", "{\n\"a\": ['x', 2],\n}\n")
            reformat()
            assertEquals("{\n    \"a\": [ \"x\", 2 ],\n}\n", myFixture.editor.document.text)
        }
    }

    fun `test whitespace-only requests change no tokens`() {
        myFixture.configureByText("a.jsonnet", "{\"a\":1,'b' : [1,2],# hash\nc:{d:1}}")
        reformat(canChangeWhiteSpaceOnly = true)
        // Quotes, `#`, field-name quoting and the missing trailing commas are all left alone.
        assertEquals("{\n  \"a\": 1,\n  'b': [1, 2],  # hash\n  c: { d: 1 }\n}\n", myFixture.editor.document.text)
    }

    fun `test the whitespace-only guard notices a changed token`() {
        // The lexer drops digit separators, which is not whitespace, so the service must refuse such a result.
        assertTrue(JsonnetTextEdits.sameApartFromWhitespace("{a:1}", "{ a: 1 }"))
        assertFalse(JsonnetTextEdits.sameApartFromWhitespace("{a:1_000}", "{ a: 1000 }"))
    }

    fun `test carets map through edits`() {
        val original = "{a:1,b:2}"
        val edits = JsonnetTextEdits.compute(original, "{ a: 1, b: 2 }")
        assertEquals("{ a: 1, ".length, JsonnetTextEdits.mapOffset(original, edits, "{a:1,".length)) // in front of `b`
        assertEquals("{ a: 1, b: 2 ".length, JsonnetTextEdits.mapOffset(original, edits, original.length - 1)) // in front of `}`
        assertEquals("{ a".length, JsonnetTextEdits.mapOffset(original, edits, "{a".length)) // right after `a`
    }

    fun `test a syntax error falls back to the block formatter instead of failing`() {
        val text = "{\na:1,,\n}"
        myFixture.configureByText("a.jsonnet", text)
        reformat()
        // Not jsonnetfmt output (there is none for broken code): the error is still there and nothing threw.
        assertTrue(myFixture.editor.document.text, com.intellij.psi.util.PsiTreeUtil.hasErrorElements(myFixture.file))
    }
}
