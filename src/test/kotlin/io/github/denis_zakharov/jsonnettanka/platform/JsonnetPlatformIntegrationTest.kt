package io.github.denis_zakharov.jsonnettanka.platform

import io.github.denis_zakharov.jsonnettanka.engine.JsonnetEngine
import io.github.denis_zakharov.jsonnettanka.engine.PathSegment
import io.github.denis_zakharov.jsonnettanka.engine.VirtualFilePath
import io.github.denis_zakharov.jsonnettanka.inspection.JsonnetUnusedDeclarationInspection
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.stubs.JsonnetBindIndex
import io.github.denis_zakharov.jsonnettanka.lang.stubs.JsonnetFieldIndex
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Retroactive coverage for the write-path/service-dependent features that
 * were "reviewed but not automated-tested" per AGENTS.md's Testing section —
 * rename (`setName`/`handleElementRename`), the native formatter's actual
 * `CodeStyleManager.reformat()` invocation, the Phase 4 stub-index build/
 * query pipeline, and the Phase 5 unused-declaration inspection's quick-fix
 * wiring — now that `BasePlatformTestCase` is confirmed working in this
 * environment (see TODO.md item 1 and the `JsonnetFileType` fix that made it
 * so: a real plugin bug — `LibsonnetFileType.getName()` not matching its
 * `plugin.xml` declaration — was corrupting stub-index initialization and
 * wedging `tearDown()`'s leak-check on a future that never completed, which
 * is what "hung indefinitely" actually was).
 */
class JsonnetPlatformIntegrationTest : BasePlatformTestCase() {

    // --- Rename (setName + handleElementRename, end-to-end via the rename refactoring) ---

    fun `test rename of top-level local updates all usages`() {
        myFixture.configureByText(
            "a.jsonnet",
            "local <caret>greeting = 'hi'; { message: greeting + '!' }",
        )
        myFixture.renameElementAtCaret("salutation")
        myFixture.checkResult("local salutation = 'hi'; { message: salutation + '!' }")
    }

    fun `test rename of function parameter updates all usages`() {
        myFixture.configureByText(
            "a.jsonnet",
            "local f(<caret>x) = x + 1; f(2)",
        )
        myFixture.renameElementAtCaret("n")
        myFixture.checkResult("local f(n) = n + 1; f(2)")
    }

    fun `test rename of field updates self reference`() {
        myFixture.configureByText(
            "a.jsonnet",
            "{ <caret>count: 1, next: self.count + 1 }",
        )
        myFixture.renameElementAtCaret("total")
        myFixture.checkResult("{ total: 1, next: self.total + 1 }")
    }

    // --- Formatter (real CodeStyleManager.reformat() invocation) ---

    fun `test reformat fixes indentation and enforces baseline spacing`() {
        myFixture.configureByText(
            "a.jsonnet",
            "{\n" +
                "a:1,\n" +
                "b : {\n" +
                "c:2,\n" +
                "},\n" +
                "}",
        )
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        myFixture.checkResult(
            "{\n" +
                "    a: 1,\n" +
                "    b: {\n" +
                "        c: 2,\n" +
                "    },\n" +
                "}",
        )
    }

    // --- Unused-declaration inspection quick fixes ---

    fun `test unused local quick fix removes the whole local expr when it is the only bind`() {
        myFixture.enableInspections(JsonnetUnusedDeclarationInspection())
        myFixture.configureByText("a.jsonnet", "local <caret>unused = 1; { a: 1 }")
        myFixture.doHighlighting()
        val intention = myFixture.findSingleIntention("Remove unused local")
        myFixture.launchAction(intention)
        myFixture.checkResult("{ a: 1 }")
    }

    fun `test unused local quick fix removes only that bind from a multi-bind local`() {
        myFixture.enableInspections(JsonnetUnusedDeclarationInspection())
        myFixture.configureByText("a.jsonnet", "local used = 1, <caret>unused = 2; used")
        val intention = myFixture.findSingleIntention("Remove unused local")
        myFixture.launchAction(intention)
        myFixture.checkResult("local used = 1; used")
    }

    fun `test unused hidden field quick fix removes the field`() {
        myFixture.enableInspections(JsonnetUnusedDeclarationInspection())
        myFixture.configureByText("a.jsonnet", "{ <caret>helper:: 1, y: 1 }")
        val intention = myFixture.findSingleIntention("Remove unused hidden field")
        myFixture.launchAction(intention)
        myFixture.checkResult("{ y: 1 }")
    }

    // --- Stub-index build/query pipeline (Phase 4) ---

    fun `test goto symbol stub index finds a top-level local across files`() {
        myFixture.configureByText("lib.jsonnet", "local Widget(name) = { name: name }; Widget")
        val scope = GlobalSearchScope.allScope(project)
        val binds = StubIndex.getElements(JsonnetBindIndex.KEY, "Widget", project, scope, JsonnetBind::class.java)
        assertEquals(1, binds.size)
    }

    fun `test goto symbol stub index finds a top-level field across files`() {
        myFixture.configureByText("lib.jsonnet", "{ exportedHelper: 1 }")
        val scope = GlobalSearchScope.allScope(project)
        val fields = StubIndex.getElements(JsonnetFieldIndex.KEY, "exportedHelper", project, scope, JsonnetField::class.java)
        assertEquals(1, fields.size)
    }

    fun `test stub index excludes a local nested inside a function body`() {
        myFixture.configureByText(
            "lib.jsonnet",
            "local f(x) = local nestedHelper = x + 1; nestedHelper; f(1)",
        )
        val scope = GlobalSearchScope.allScope(project)
        val binds = StubIndex.getElements(JsonnetBindIndex.KEY, "nestedHelper", project, scope, JsonnetBind::class.java)
        assertEquals(0, binds.size)
    }

    // --- VirtualFileImporter / VirtualFilePath (Phase 2), against the real IDE VFS ---

    fun `test import resolves relative to the importing file via the real VFS`() {
        myFixture.addFileToProject("lib/greeter.libsonnet", "{ greet(name):: 'Hello, ' + name + '!' }")
        val main = myFixture.addFileToProject(
            "main.jsonnet",
            "local greeter = import 'lib/greeter.libsonnet'; greeter.greet('World')",
        )
        val result = JsonnetEngine.evaluateFile(main.virtualFile)
        assertTrue(
            "expected a successful evaluation, got: $result",
            result is JsonnetEngine.Result.Success,
        )
        assertEquals("\"Hello, World!\"", (result as JsonnetEngine.Result.Success).output)
    }

    // --- Unsaved editor buffers (TODO.md item 5) + Preview click-jump across files (item 4) ---

    private fun setUnsavedText(file: com.intellij.openapi.vfs.VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        assertTrue("edit should still be unsaved", FileDocumentManager.getInstance().isFileModified(file))
    }

    private fun output(result: JsonnetEngine.Result): String {
        assertTrue("expected a successful evaluation, got: $result", result is JsonnetEngine.Result.Success)
        return (result as JsonnetEngine.Result.Success).output
    }

    fun `test evaluation sees unsaved edits to an imported file`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ v: 1 }")
        val main = myFixture.addFileToProject("main.jsonnet", "(import 'lib.libsonnet').v")
        setUnsavedText(lib.virtualFile, "{ v: 2 }")
        assertEquals("2", output(JsonnetEngine.evaluateFile(main.virtualFile)))
    }

    fun `test evaluation sees unsaved edits to the evaluated file itself`() {
        val main = myFixture.addFileToProject("main.jsonnet", "1")
        setUnsavedText(main.virtualFile, "40 + 2")
        assertEquals("42", output(JsonnetEngine.evaluateFile(main.virtualFile)))
    }

    fun `test locate jumps into an imported file`() {
        val libText = "{ deep: { leaf: 1 } }"
        val lib = myFixture.addFileToProject("lib.libsonnet", libText)
        val main = myFixture.addFileToProject("main.jsonnet", "{ x: (import 'lib.libsonnet').deep }")
        val result = JsonnetEngine.evaluateFile(main.virtualFile) as JsonnetEngine.Result.Success
        val location = result.locator!!.locate(listOf(PathSegment.Key("x"), PathSegment.Key("leaf")))!!
        assertEquals(lib.virtualFile, (location.path as VirtualFilePath).file)
        assertEquals(libText.indexOf("1"), location.offset)
    }

    fun `test locate offsets refer to the unsaved buffer text`() {
        val main = myFixture.addFileToProject("main.jsonnet", "{ a: 1 }")
        val edited = "{\n  pad: 0,\n  a: 1,\n}"
        setUnsavedText(main.virtualFile, edited)
        val result = JsonnetEngine.evaluateFile(main.virtualFile) as JsonnetEngine.Result.Success
        val location = result.locator!!.locate(listOf(PathSegment.Key("a")))!!
        assertEquals(edited.indexOf("1"), location.offset)
    }

    // --- Preview panel wiring (TODO.md item 4): focus -> evaluate -> click-jump ---

    private fun previewPanelFor(fileName: String, text: String): io.github.denis_zakharov.jsonnettanka.editor.preview.JsonnetPreviewPanel {
        myFixture.configureByText(fileName, text)
        val panel = io.github.denis_zakharov.jsonnettanka.editor.preview.JsonnetPreviewPanel(project)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, panel)
        return panel
    }

    fun `test preview panel renders the focused file and jumps to the source of a clicked line`() {
        val text = "{\n  a: 1,\n  b: { c: 'x' },\n}"
        val panel = previewPanelFor("main.jsonnet", text)
        val output = panel.outputText
        assertTrue("panel should show the evaluation, got: $output", output.contains("\"c\": \"x\""))

        val line = output.lines().indexOfFirst { it.contains("\"c\"") }
        panel.jumpToLine(line)
        assertEquals(text.indexOf("'x'"), myFixture.editor.caretModel.offset)
    }

    fun `test preview panel yaml mode renders yaml and still jumps`() {
        val text = "{\n  a: 1,\n  list: [10, 20],\n}"
        val panel = previewPanelFor("main.jsonnet", text)
        panel.yamlEnabled = true
        panel.refresh()
        val output = panel.outputText
        assertTrue("expected YAML, got: $output", output.contains("list:") && output.contains("- 20"))

        panel.jumpToLine(output.lines().indexOfFirst { it.contains("- 20") })
        assertEquals(text.indexOf("20"), myFixture.editor.caretModel.offset)
    }

    fun `test preview jump into an imported file does not retarget the preview`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ leaf: 7 }")
        val panel = previewPanelFor("main.jsonnet", "{ x: (import 'lib.libsonnet') }")
        val before = panel.outputText

        panel.jumpToLine(panel.outputText.lines().indexOfFirst { it.contains("leaf") })
        val editorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        assertEquals(lib.virtualFile, editorManager.selectedEditor?.file)
        assertEquals("{ leaf: 7 }".indexOf("7"), editorManager.selectedTextEditor?.caretModel?.offset)
        assertEquals("preview must keep showing main.jsonnet's output", before, panel.outputText)
    }

    // --- ColorSettingsPage (TODO.md item 6) ---

    fun `test color settings page is registered and covers every semantic highlighting key`() {
        val pages = com.intellij.openapi.options.colors.ColorSettingsPages.getInstance().registeredPages
        assertTrue("page should be registered via plugin.xml", pages.any { it is io.github.denis_zakharov.jsonnettanka.editor.JsonnetColorSettingsPage })

        val page = io.github.denis_zakharov.jsonnettanka.editor.JsonnetColorSettingsPage()
        val keys = page.attributeDescriptors.map { it.key }.toSet()
        val semantic = io.github.denis_zakharov.jsonnettanka.editor.JsonnetSemanticHighlightingAnnotator
        assertTrue(keys.containsAll(listOf(semantic.LOCAL_VARIABLE, semantic.PARAMETER, semantic.FIELD, semantic.STD_CALL)))
        assertEquals("every descriptor key appears once", page.attributeDescriptors.size, keys.size)
    }

    fun `test color settings demo text is valid jsonnet and every tag is mapped`() {
        val page = io.github.denis_zakharov.jsonnettanka.editor.JsonnetColorSettingsPage()
        val tagged = page.demoText
        val usedTags = Regex("</?([a-z]+)>").findAll(tagged).map { it.groupValues[1] }.toSet()
        assertEquals(page.additionalHighlightingTagToDescriptorMap.keys, usedTags)

        val stripped = Regex("</?[a-z]+>").replace(tagged, "")
        myFixture.configureByText("demo.jsonnet", stripped)
        assertEquals("demo text should highlight without errors", emptyList<String>(), errorRanges().map { stripped.substring(it.startOffset, it.endOffset) })
        val result = JsonnetEngine.evaluate("demo.jsonnet", stripped)
        assertTrue("demo text should evaluate, got: $result", result is JsonnetEngine.Result.Success)
    }

    // --- JsonnetUnresolvedReferenceAnnotator (Phase 2) + SjsonnetStaticCheck cross-check (TODO.md item 3) ---

    private fun errorRanges(): List<com.intellij.openapi.util.TextRange> =
        myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            .map { com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset) }

    fun `test resolved identifier is not flagged`() {
        myFixture.configureByText("a.jsonnet", "local x = 1; { a: x, b: x + 1 }")
        assertEquals(emptyList<com.intellij.openapi.util.TextRange>(), errorRanges())
    }

    fun `test std is never flagged even though it has no declaration`() {
        myFixture.configureByText("a.jsonnet", "std.length([1, 2, 3])")
        assertEquals(emptyList<com.intellij.openapi.util.TextRange>(), errorRanges())
    }

    fun `test genuinely unresolved identifier is flagged exactly once`() {
        val text = "local x = 1; x + totallyUndefined"
        myFixture.configureByText("a.jsonnet", text)
        val errors = errorRanges()
        assertEquals(1, errors.size)
        assertEquals("totallyUndefined", text.substring(errors.single().startOffset, errors.single().endOffset))
    }

    fun `test function-sugar bind parameter is not flagged inside its own body`() {
        // Locks in the JsonnetResolver.resolveLocalName fix (see AGENTS.md's
        // Resolver bugs section) at the full annotator-integration level, not
        // just the unit-test level: this used to render as a false "unresolved
        // reference" error on every use of the single most common Jsonnet idiom.
        myFixture.configureByText("a.jsonnet", "local f(x) = x + 1; f(2)")
        assertEquals(emptyList<com.intellij.openapi.util.TextRange>(), errorRanges())
    }
}
