package io.github.denis_zakharov.jsonnettanka.platform

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.denis_zakharov.jsonnettanka.inspection.JsonnetSjsonnetOnlyStdInspection

/** `std` members only sjsonnet has preview fine and then fail under `tk`; the inspection has to say so up front. */
class JsonnetSjsonnetOnlyStdInspectionTest : BasePlatformTestCase() {

    private fun warnings(path: String, text: String): List<Pair<String, String>> {
        myFixture.enableInspections(JsonnetSjsonnetOnlyStdInspection())
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
        return myFixture.doHighlighting()
            .filter { it.description?.contains("exists only in sjsonnet") == true }
            .map { (it.text) to it.severity.name }
    }

    fun `test each sjsonnet-only function is flagged on its name as a warning`() {
        for (name in listOf("regexFullMatch", "regexPartialMatch", "regexGlobalReplace", "regexReplace", "regexQuoteMeta")) {
            assertEquals(listOf(name to HighlightSeverity.WARNING.name), warnings("$name.jsonnet", "std.$name('a', 'b')"))
        }
    }

    fun `test the message says why it matters and what to use instead`() {
        myFixture.enableInspections(JsonnetSjsonnetOnlyStdInspection())
        myFixture.configureByText("m.jsonnet", "std.regexQuoteMeta('a.b')")
        val message = myFixture.doHighlighting().mapNotNull { it.description }.single { it.startsWith("'std.regexQuoteMeta'") }
        assertTrue(message, "under 'tk'" in message)
        assertTrue(message, "std.native('escapeStringRegex')" in message)
    }

    fun `test functions go-jsonnet has are not flagged`() {
        assertEmpty(warnings("ok.jsonnet", "std.length('a') + std.native('regexMatch')('a', 'b') + std.parseYaml('a')"))
    }

    fun `test a same-named field on another object is not flagged`() {
        assertEmpty(warnings("other.jsonnet", "local o = { regexQuoteMeta(s):: s }; o.regexQuoteMeta('x')"))
    }

    fun `test a local that shadows std is not flagged`() {
        assertEmpty(warnings("shadow.jsonnet", "local std = { regexQuoteMeta(s):: s }; std.regexQuoteMeta('x')"))
    }

    fun `test a function parameter named std shadows it too`() {
        assertEmpty(warnings("param.jsonnet", "function(std) std.regexReplace('a', 'b', 'c')"))
    }

    fun `test every use in a file is flagged`() {
        val text = "{ a: std.regexQuoteMeta('x'), b: std.regexReplace('a', 'b', 'c'), c: std.regexQuoteMeta('y') }"
        assertEquals(3, warnings("many.jsonnet", text).size)
    }

    fun `test vendored files are not inspected`() {
        assertEmpty(warnings("vendor/pkg/lib.libsonnet", "std.regexQuoteMeta('x')"))
    }
}
