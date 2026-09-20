package com.dz.intellijjsonnet.platform

import com.dz.intellijjsonnet.engine.JsonnetEngine
import com.dz.intellijjsonnet.inspection.JsonnetUnusedDeclarationInspection
import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.stubs.JsonnetBindIndex
import com.dz.intellijjsonnet.lang.stubs.JsonnetFieldIndex
import com.intellij.openapi.command.WriteCommandAction
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
        assertEquals("\"Hello, World!\"", (result as JsonnetEngine.Result.Success).json)
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
