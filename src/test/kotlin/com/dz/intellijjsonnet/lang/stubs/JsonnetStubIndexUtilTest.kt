package com.dz.intellijjsonnet.lang.stubs

import com.dz.intellijjsonnet.lang.JsonnetParserDefinition
import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

/**
 * [JsonnetStubIndexUtil] is what actually decides which declarations end up
 * in [JsonnetBindIndex]/[JsonnetFieldIndex] (via each stub element type's
 * `createStub`) — this is the part worth locking down with tests, independent
 * of whether a real stub tree gets built (that also needs a `PsiFileFactory`/
 * VFS-backed `PsiFile`, which is the same wall Phase 2-4 hit for other
 * write-path/service-dependent code; see AGENTS.md).
 */
class JsonnetStubIndexUtilTest : ParsingTestCase("", "jsonnet", JsonnetParserDefinition()) {

    override fun getTestDataPath(): String = "."
    override fun skipSpaces(): Boolean = true

    private fun binds(text: String): List<JsonnetBind> {
        val file = createPsiFile("test", text)
        ensureParsed(file)
        return PsiTreeUtil.collectElementsOfType(file, JsonnetBind::class.java).toList()
    }

    private fun fields(text: String): List<JsonnetField> {
        val file = createPsiFile("test", text)
        ensureParsed(file)
        return PsiTreeUtil.collectElementsOfType(file, JsonnetField::class.java).toList()
    }

    fun `test leading local chain is top level`() {
        val bs = binds("local a = 1; local b = 2; a + b")
        assertEquals(setOf("a", "b"), bs.map { it.nameIdentifier?.text }.toSet())
        bs.forEach { assertTrue("${it.nameIdentifier?.text} should be top-level", JsonnetStubIndexUtil.isTopLevelBindDecl(it)) }
    }

    fun `test object-scoped local is not top level`() {
        val b = binds("{ local secret = 'shh', x: secret }").single { it.nameIdentifier?.text == "secret" }
        assertFalse(JsonnetStubIndexUtil.isTopLevelBindDecl(b))
    }

    fun `test local inside a function body is not top level`() {
        val b = binds("local f = function(x) local y = x + 1; y; f(1)").single { it.nameIdentifier?.text == "y" }
        assertFalse(JsonnetStubIndexUtil.isTopLevelBindDecl(b))
    }

    fun `test root object field is top level`() {
        val f = fields("{ a: 1 }").single()
        assertTrue(JsonnetStubIndexUtil.isTopLevelFieldDecl(f))
    }

    fun `test field nested under a top-level field is still top level`() {
        val f = fields("{ a: { b: 1 } }").single { it.text.startsWith("b") }
        assertTrue(JsonnetStubIndexUtil.isTopLevelFieldDecl(f))
    }

    fun `test field on the value of a top-level bind is top level`() {
        val f = fields("local Foo = { new():: {} }; Foo").single()
        assertTrue(JsonnetStubIndexUtil.isTopLevelFieldDecl(f))
        assertTrue("factory field should have params", f.paramList != null)
    }

    fun `test field composed via plus onto a top-level value is top level`() {
        val f = fields("local Base = {}; Base + { name: 'x' }").single()
        assertTrue(JsonnetStubIndexUtil.isTopLevelFieldDecl(f))
    }

    fun `test field inside a function body is not top level`() {
        val f = fields("local f = function() { a: 1 }; f()").single()
        assertFalse(JsonnetStubIndexUtil.isTopLevelFieldDecl(f))
    }

    fun `test field inside an array literal is not top level`() {
        val f = fields("[{ a: 1 }]").single()
        assertFalse(JsonnetStubIndexUtil.isTopLevelFieldDecl(f))
    }

    fun `test vendored path detection`() {
        assertTrue(JsonnetStubIndexUtil.isVendoredPath("/repo/vendor/github.com/foo/bar.libsonnet"))
        assertTrue(JsonnetStubIndexUtil.isVendoredPath("/repo/vendor/lib.libsonnet"))
        assertFalse(JsonnetStubIndexUtil.isVendoredPath("/repo/lib/bar.libsonnet"))
        assertFalse(JsonnetStubIndexUtil.isVendoredPath("/repo/vendored-but-not-quite/bar.libsonnet"))
    }
}
