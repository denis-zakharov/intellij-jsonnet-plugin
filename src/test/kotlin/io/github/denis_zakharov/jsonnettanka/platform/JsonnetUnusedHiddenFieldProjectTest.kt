package io.github.denis_zakharov.jsonnettanka.platform

import io.github.denis_zakharov.jsonnettanka.inspection.JsonnetUnusedDeclarationInspection
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Hidden fields are Jsonnet's "private" marker but also how libraries expose their API
 * (`lib.new():: ...`), so "unused" has to be judged across the project, not inside one object.
 * The rule: a field is only reported when no other same-named access could refer to it —
 * an access that resolves elsewhere doesn't count, one we can't resolve (unknown receiver) does.
 */
class JsonnetUnusedHiddenFieldProjectTest : BasePlatformTestCase() {

    private fun hiddenFieldWarnings(file: PsiFile): List<String> {
        myFixture.enableInspections(JsonnetUnusedDeclarationInspection())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.doHighlighting().mapNotNull { it.description }.filter { it.startsWith("Hidden field") }
    }

    fun `test field used from an importing file is not reported`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1, other: 2 }")
        myFixture.addFileToProject("main.jsonnet", "local l = import 'lib.libsonnet'; { x: l.helper }")
        assertEmpty(hiddenFieldWarnings(lib))
    }

    fun `test field used through a function call result is not reported`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ new():: { helper:: 1, value: 2 } }")
        myFixture.addFileToProject("main.jsonnet", "local l = import 'lib.libsonnet'; { x: l.new().helper }")
        assertEmpty(hiddenFieldWarnings(lib))
    }

    fun `test access through an unresolvable receiver counts as a use`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1 }")
        myFixture.addFileToProject("main.jsonnet", "function(o) o.helper")
        assertEmpty(hiddenFieldWarnings(lib))
    }

    fun `test string key access counts as a use`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1 }")
        myFixture.addFileToProject("main.jsonnet", "function(o) std.objectHasAll(o, 'helper')")
        assertEmpty(hiddenFieldWarnings(lib))
    }

    fun `test field nobody references is reported`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1, other: 2 }")
        myFixture.addFileToProject("main.jsonnet", "local l = import 'lib.libsonnet'; { x: l.other }")
        assertEquals(listOf("Hidden field 'helper' is never used in this project"), hiddenFieldWarnings(lib))
    }

    fun `test a same-named access that resolves to another object is not a use`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1 }")
        myFixture.addFileToProject("main.jsonnet", "local o = { helper: 2 }; o.helper")
        assertEquals(listOf("Hidden field 'helper' is never used in this project"), hiddenFieldWarnings(lib))
    }

    fun `test mentions in comments are not uses`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1 }")
        myFixture.addFileToProject("main.jsonnet", "// call lib.helper someday\n{}")
        assertEquals(listOf("Hidden field 'helper' is never used in this project"), hiddenFieldWarnings(lib))
    }

    fun `test vendored files are not inspected`() {
        val vendored = myFixture.addFileToProject("vendor/pkg/lib.libsonnet", "{ helper:: 1 }")
        assertEmpty(hiddenFieldWarnings(vendored))
    }

    fun `test field used inside its own file is still not reported`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1, y: self.helper }")
        assertEmpty(hiddenFieldWarnings(lib))
    }

    // `withX():: { x+:: ... }` patches the base `x::` that `new()` declares; `self.x` only resolves to the
    // base, but what it reads at runtime is the merged object, so the mixin field is used too.
    fun `test mixin field merging into a same-named base field is not reported`() {
        val lib = myFixture.addFileToProject(
            "lib.libsonnet",
            "{ new():: { local app = self, props:: {}, out: app.props }, withProps():: { props+:: { a: 1 } } }",
        )
        myFixture.addFileToProject("main.jsonnet", "local l = import 'lib.libsonnet'; l.new() + l.withProps()")
        assertEmpty(hiddenFieldWarnings(lib))
    }

    // `c+:: { logging:: null }` exists for its visibility: it hides the `logging:` another mixin adds.
    fun `test hiding a field inside a merged value is not reported`() {
        val lib = myFixture.addFileToProject(
            "lib.libsonnet",
            """
            {
              new():: { local app = self, c:: {}, out: std.objectValues(app.c) },
              withLogging():: { c+:: { logging: 1 } },
              withoutLogging():: { c+:: { logging:: null } },
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "main.jsonnet",
            "local l = import 'lib.libsonnet'; l.new() + l.withLogging() + l.withoutLogging()",
        )
        assertEmpty(hiddenFieldWarnings(lib))
    }

    fun `test mixin field with no base and no reads is still reported`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ helper+:: 1 }")
        assertEquals(listOf("Hidden field 'helper' is never used in this project"), hiddenFieldWarnings(lib))
    }

    fun `test hidden field inside a merged value with no same-named field elsewhere is still reported`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{ w():: { c+: { extra:: 1 } } }")
        myFixture.addFileToProject("main.jsonnet", "local l = import 'lib.libsonnet'; l.w()")
        assertEquals(listOf("Hidden field 'extra' is never used in this project"), hiddenFieldWarnings(lib))
    }
}
