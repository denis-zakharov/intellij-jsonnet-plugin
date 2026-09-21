package io.github.denis_zakharov.jsonnettanka.platform

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Go-to-definition through member access chains and basic completion — the `lib.foo.bar`,
 * `self`/`super`/`$`, `+`/`{...}` composition and function-call shapes real Tanka code is made of.
 */
class JsonnetNavigationCompletionTest : BasePlatformTestCase() {

    private fun resolveAtCaret(text: String): List<PsiElement> {
        myFixture.configureByText("main.jsonnet", text)
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as PsiPolyVariantReference
        return reference.multiResolve(false).mapNotNull { it.element }
    }

    /** `file:line-text` of each resolved field, e.g. `lib.libsonnet:deep: 2` — pins down *which* declaration, not just "something". */
    private fun describe(targets: List<PsiElement>): List<String> =
        targets.map { "${it.containingFile.name}:${(it as JsonnetField).text}" }

    private fun assertResolvesTo(text: String, vararg expected: String) =
        assertEquals(expected.toList(), describe(resolveAtCaret(text)))

    // --- go to definition ---

    fun `test field through import and nested object`() {
        myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1, nested: { deep: 2 } }")
        assertResolvesTo(
            "local lib = import 'lib.libsonnet'; lib.nested.<caret>deep",
            "lib.libsonnet:deep: 2",
        )
    }

    fun `test import found through a local wrapper and a hidden field`() {
        myFixture.addFileToProject("lib.libsonnet", "local h = { x:: 1 }; h")
        assertResolvesTo("(import 'lib.libsonnet').<caret>x", "lib.libsonnet:x:: 1")
    }

    fun `test self sees fields of the base it extends`() {
        assertResolvesTo(
            "local base = { a: 1 }; base { b: self.<caret>a }",
            "main.jsonnet:a: 1",
        )
    }

    fun `test self sees the left operand of plus`() {
        assertResolvesTo("{ a: 1 } + { b: self.<caret>a }", "main.jsonnet:a: 1")
    }

    fun `test super resolves to the base field`() {
        assertResolvesTo(
            "local base = { a: 1 }; base { a: super.<caret>a + 1 }",
            "main.jsonnet:a: 1",
        )
    }

    fun `test dollar is the outermost enclosing object, not the file root`() {
        assertResolvesTo(
            "local f = { x: 1, y: \$.<caret>x }; { z: 1 }",
            "main.jsonnet:x: 1",
        )
    }

    fun `test plus composition yields every declaring object`() {
        assertResolvesTo(
            "({ a: 1 } + { a: 2 }).<caret>a",
            "main.jsonnet:a: 1",
            "main.jsonnet:a: 2",
        )
    }

    fun `test call of a local function returns its body's object`() {
        assertResolvesTo(
            "local mk(n) = { name: n }; mk('x').<caret>name",
            "main.jsonnet:name: n",
        )
    }

    fun `test call of a function-valued field`() {
        assertResolvesTo(
            "local lib = { new(n):: { name: n } }; lib.new('x').<caret>name",
            "main.jsonnet:name: n",
        )
    }

    fun `test plus-colon field extends the overridden field`() {
        assertResolvesTo(
            "local base = { spec: { replicas: 1 } }; (base { spec+: { image: 'x' } }).spec.<caret>replicas",
            "main.jsonnet:replicas: 1",
        )
    }

    fun `test string index selects a field`() {
        assertResolvesTo("local o = { x: { y: 1 } }; o['x'].<caret>y", "main.jsonnet:y: 1")
    }

    fun `test field of a parenthesised if expression`() {
        assertResolvesTo(
            "local a = { k: 1 }; local b = { k: 2 }; (if true then a else b).<caret>k",
            "main.jsonnet:k: 1",
            "main.jsonnet:k: 2",
        )
    }

    fun `test unknown receivers resolve to nothing without throwing`() {
        assertEquals(emptyList<String>(), describe(resolveAtCaret("std.<caret>length([])")))
        assertEquals(emptyList<String>(), describe(resolveAtCaret("function(p) p.<caret>x")))
        assertEquals(emptyList<String>(), describe(resolveAtCaret("import 'missing.libsonnet' + {}.<caret>x")))
    }

    fun `test self-referential locals terminate`() {
        assertEquals(emptyList<String>(), describe(resolveAtCaret("local a = a.b; a.<caret>b")))
        assertEquals(emptyList<String>(), describe(resolveAtCaret("local a = b, b = a; a.<caret>x")))
    }

    fun `test files that import each other terminate`() {
        myFixture.addFileToProject("a.libsonnet", "(import 'b.libsonnet') + { fromA: 1 }")
        myFixture.addFileToProject("b.libsonnet", "(import 'a.libsonnet') + { fromB: 1 }")
        assertResolvesTo("(import 'a.libsonnet').<caret>fromB", "b.libsonnet:fromB: 1")
    }

    fun `test rename of a field renames its use through an import`() {
        val libFile = myFixture.addFileToProject("lib.libsonnet", "{ helper: 1 }")
        myFixture.configureByText("main.jsonnet", "local lib = import 'lib.libsonnet'; lib.<caret>helper")
        myFixture.renameElementAtCaret("assist")
        myFixture.checkResult("local lib = import 'lib.libsonnet'; lib.assist")
        assertEquals("{ assist: 1 }", libFile.text)
    }

    // --- import paths (were never wired up: a bare STRING leaf doesn't ask reference contributors) ---

    fun `test go to definition on an import path opens the file`() {
        myFixture.addFileToProject("lib/util.libsonnet", "{}")
        myFixture.configureByText("main.jsonnet", "local u = import 'lib/ut<caret>il.libsonnet'; u")
        val target = myFixture.getReferenceAtCaretPositionWithAssertion().resolve()
        assertEquals("util.libsonnet", (target as com.intellij.psi.PsiFile).name)
    }

    fun `test import path resolves through vendor like tanka does`() {
        myFixture.addFileToProject("jsonnetfile.json", "{}")
        myFixture.addFileToProject("vendor/pkg/main.libsonnet", "{ fromVendor: 1 }")
        myFixture.addFileToProject("environments/dev/main.jsonnet", "")
        myFixture.configureFromExistingVirtualFile(
            myFixture.addFileToProject(
                "environments/dev/spec.jsonnet",
                "local p = import 'pkg/main.libsonnet'; p.<caret>fromVendor",
            ).virtualFile,
        )
        val reference = myFixture.getReferenceAtCaretPositionWithAssertion() as PsiPolyVariantReference
        assertEquals(listOf("main.libsonnet:fromVendor: 1"), describe(reference.multiResolve(false).mapNotNull { it.element }))
    }

    fun `test import tk is not an unresolved file reference`() {
        myFixture.configureByText("main.jsonnet", "local tk = import 't<caret>k'; tk")
        assertNull(myFixture.file.findReferenceAt(myFixture.caretOffset))
    }

    fun `test moving a file rewrites the import path`() {
        val lib = myFixture.addFileToProject("lib.libsonnet", "{}")
        myFixture.configureByText("main.jsonnet", "local l = import 'lib.libsonnet'; l")
        myFixture.renameElement(lib, "renamed.libsonnet")
        myFixture.checkResult("local l = import 'renamed.libsonnet'; l")
    }

    // --- completion ---

    private fun complete(text: String): List<String> {
        myFixture.configureByText("main.jsonnet", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun `test completes fields of an imported module`() {
        myFixture.addFileToProject("lib.libsonnet", "{ helper:: 1, nested: { deep: 2 }, 'odd-name': 3 }")
        val items = complete("local lib = import 'lib.libsonnet'; lib.<caret>")
        assertSameElements(items, "helper", "nested")
    }

    fun `test completes nested fields`() {
        myFixture.addFileToProject("lib.libsonnet", "{ nested: { deep: 2, deeper: 3 } }")
        assertSameElements(
            complete("local lib = import 'lib.libsonnet'; lib.nested.<caret>"),
            "deep", "deeper",
        )
    }

    fun `test completes self fields`() {
        assertSameElements(complete("{ alpha: 1, beta: self.<caret> }"), "alpha", "beta")
    }

    fun `test completes fields after a call`() {
        assertSameElements(
            complete("local mk(n) = { name: n, size: 1 }; mk('x').<caret>"),
            "name", "size",
        )
    }

    fun `test completes only what is in scope`() {
        val inside = complete("local alpha = 1; local f(beta) = <caret>; f(1)")
        assertContainsElements(inside, "alpha", "beta", "f")
        val outside = complete("local f(beta) = 1; <caret>")
        assertContainsElements(outside, "f")
        assertDoesntContain(outside, "beta")
    }

    fun `test inner names shadow outer ones once`() {
        val items = complete("local x = 1; local g(x) = <caret>; g(2)")
        assertEquals(1, items.count { it == "x" })
    }

    fun `test completes loop variables`() {
        assertContainsElements(complete("[<caret> for item in [1, 2]]"), "item")
    }

    fun `test completes keywords and std where an expression starts`() {
        assertContainsElements(complete("{ a: <caret> }"), "std", "self", "local", "if", "function", "import", "error", "null")
    }

    fun `test import keyword inserts quotes`() {
        myFixture.configureByText("main.jsonnet", "{ a: imp<caret> }")
        myFixture.completeBasic()
        val item = myFixture.lookup.items.first { it.lookupString == "import" }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        myFixture.checkResult("{ a: import '<caret>' }")
    }

    fun `test function fields and locals get parentheses`() {
        myFixture.configureByText("main.jsonnet", "local lib = { newThing(a, b):: 1 }; lib.newTh<caret>")
        myFixture.completeBasic()
        myFixture.checkResult("local lib = { newThing(a, b):: 1 }; lib.newThing(<caret>)")
    }

    fun `test completes import paths`() {
        myFixture.addFileToProject("libs/one.libsonnet", "{}")
        myFixture.addFileToProject("libs/two.libsonnet", "{}")
        assertContainsElements(complete("local x = import 'libs/<caret>';"), "one.libsonnet", "two.libsonnet")
    }
}
