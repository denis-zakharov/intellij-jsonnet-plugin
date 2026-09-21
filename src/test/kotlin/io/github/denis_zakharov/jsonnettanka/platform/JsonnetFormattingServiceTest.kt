package io.github.denis_zakharov.jsonnettanka.platform

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.util.TextRange
import io.github.denis_zakharov.jsonnettanka.formatter.JsonnetFormatExclusions
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

    fun `test a whitespace-only reformat refuses a file with digit separators`() {
        val text = "{a:1_000}"
        myFixture.configureByText("a.jsonnet", text)
        assertFalse(com.intellij.psi.util.PsiTreeUtil.hasErrorElements(myFixture.file)) // reaches the service now
        reformat(canChangeWhiteSpaceOnly = true)
        // The port would write 1000; that is not a whitespace change, so nothing happens.
        assertEquals(text, myFixture.editor.document.text)
        reformat()
        assertEquals("{ a: 1000 }\n", myFixture.editor.document.text)
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

    // --- item 17: vendor/ and dot-files ---

    fun `test exclusion rules follow tk fmt`() {
        val base = "/work/env"
        for (excluded in listOf("/work/env/vendor/k.libsonnet", "/work/env/lib/vendor/x/y.libsonnet", "/work/env/.hidden.jsonnet", "/work/env/.git/x.jsonnet")) {
            assertTrue(excluded, JsonnetFormatExclusions.isExcluded(excluded, base))
        }
        for (included in listOf("/work/env/main.jsonnet", "/work/env/lib/vendored.libsonnet", "/work/env/vendor.jsonnet")) {
            assertFalse(included, JsonnetFormatExclusions.isExcluded(included, base))
        }
        // The project itself may live in a hidden directory; only what is below the root counts.
        assertFalse(JsonnetFormatExclusions.isExcluded("/home/u/.work/env/main.jsonnet", "/home/u/.work/env"))
        assertTrue(JsonnetFormatExclusions.isExcluded("/home/u/.work/env/vendor/a.libsonnet", "/home/u/.work/env"))
        // Outside the project (or without one) only the file name and `vendor` directories are judged.
        assertFalse(JsonnetFormatExclusions.isExcluded("/home/u/.work/main.jsonnet", null))
        assertTrue(JsonnetFormatExclusions.isExcluded("/x/vendor/a.libsonnet", null))
    }

    fun `test Reformat Code leaves vendor files alone`() {
        val text = "{a:1}"
        val file = myFixture.addFileToProject("vendor/github.com/x/y.libsonnet", text).virtualFile
        myFixture.configureFromExistingVirtualFile(file)
        reformat()
        assertEquals(text, myFixture.editor.document.text)
    }

    fun `test Reformat Code leaves dot-files alone`() {
        val text = "{a:1}"
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(".hidden.jsonnet", text).virtualFile)
        reformat()
        assertEquals(text, myFixture.editor.document.text)
    }

    fun `test the vendor exclusion can be turned off`() {
        CodeStyle.runWithLocalSettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCustomSettings(JsonnetCodeStyleSettings::class.java).SKIP_VENDOR_AND_DOTFILES = false
            myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("vendor/y.libsonnet", "{a:1}").virtualFile)
            reformat()
            assertEquals("{ a: 1 }\n", myFixture.editor.document.text)
        }
    }

    // --- item 18: the other entry points ---

    fun `test several ranges format only those ranges`() {
        // "Only changed text" hands the VCS ranges over; the middle line is not in any of them.
        val text = "local a = {x:1,   y:2};\nlocal b = {x:1,   y:2};\nlocal c = {x:1,   y:2};\n{a:a,b:b,c:c}\n"
        myFixture.configureByText("a.jsonnet", text)
        val second = text.indexOf("local b")
        val third = text.indexOf("local c")
        val ranges = arrayOf(TextRange(0, second - 1), TextRange(third, text.indexOf("{a:a") - 1))
        ReformatCodeProcessor(myFixture.file, ranges).run()
        myFixture.checkResult(
            "local a = { x: 1, y: 2 };\nlocal b = {x:1,   y:2};\nlocal c = { x: 1, y: 2 };\n{a:a,b:b,c:c}\n",
        )
    }

    fun `test reformat of a directory covers the jsonnet files and skips vendor`() {
        val a = myFixture.addFileToProject("env/a.jsonnet", "{a:1}")
        val b = myFixture.addFileToProject("env/lib/b.libsonnet", "{b:2}")
        val v = myFixture.addFileToProject("env/vendor/v.libsonnet", "{v:3}")
        val other = myFixture.addFileToProject("env/notes.txt", "{c:4}")
        ReformatCodeProcessor(project, a.parent, true, false).run()
        assertEquals("{ a: 1 }\n", a.text)
        assertEquals("{ b: 2 }\n", b.text)
        assertEquals("{v:3}", v.text)
        assertEquals("{c:4}", other.text)
    }

    fun `test Reformat File with optimize imports`() {
        // No import optimizer is registered (sorting is part of jsonnetfmt), so this must simply reformat.
        myFixture.configureByText("a.jsonnet", "local b = import 'b.libsonnet';\nlocal a = import 'a.libsonnet';\n{a:a,b:b}\n")
        OptimizeImportsProcessor(ReformatCodeProcessor(myFixture.file, false)).run()
        myFixture.checkResult("local a = import 'a.libsonnet';\nlocal b = import 'b.libsonnet';\n{ a: a, b: b }\n")
    }

    fun `test reformat on save`() {
        // The platform's on-save action needs a real frame (it runs from a coroutine off saveAllDocuments, which does
        // nothing here), so this replays what FormatOnSaveAction does: skip files that don't allow auto-format, then run
        // a ReformatCodeProcessor over the saved files.
        myFixture.configureByText("a.jsonnet", "{a:1}")
        assertTrue(com.intellij.lang.LanguageFormatting.INSTANCE.isAutoFormatAllowed(myFixture.file))
        ReformatCodeProcessor(project, arrayOf(myFixture.file), null, false).run()
        assertEquals("{ a: 1 }\n", myFixture.editor.document.text)
    }

    fun `test a file the port rejects is left alone and the user is told`() {
        // Duplicate fields are fine for our PSI parser but go-jsonnet's parser refuses them.
        val text = "{a:1,a:2}"
        myFixture.configureByText("a.jsonnet", text)
        assertFalse(com.intellij.psi.util.PsiTreeUtil.hasErrorElements(myFixture.file))
        val notifications = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
        reformat()
        assertEquals(text, myFixture.editor.document.text)
        val ours = notifications.filter { it.groupId == "Jsonnet" }
        assertEquals(notifications.toString(), 1, ours.size)
        assertTrue(ours[0].title, ours[0].title.startsWith("Can't format"))
    }
}
