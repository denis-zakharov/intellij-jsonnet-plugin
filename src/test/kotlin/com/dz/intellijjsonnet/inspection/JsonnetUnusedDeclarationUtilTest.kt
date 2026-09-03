package com.dz.intellijjsonnet.inspection

import com.dz.intellijjsonnet.lang.JsonnetParserDefinition
import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

/** [JsonnetUnusedDeclarationInspection] itself needs a real `PsiFileFactory`/document-commit service to exercise (see AGENTS.md); this covers the pure detection logic it wraps. */
class JsonnetUnusedDeclarationUtilTest : ParsingTestCase("", "jsonnet", JsonnetParserDefinition()) {

    override fun getTestDataPath(): String = "."
    override fun skipSpaces(): Boolean = true

    private fun bind(text: String, name: String): JsonnetBind {
        val file = createPsiFile("test", text)
        ensureParsed(file)
        return PsiTreeUtil.collectElementsOfType(file, JsonnetBind::class.java).single { it.nameIdentifier?.text == name }
    }

    private fun field(text: String, name: String): JsonnetField {
        val file = createPsiFile("test", text)
        ensureParsed(file)
        return PsiTreeUtil.collectElementsOfType(file, JsonnetField::class.java).single { JsonnetResolver.fieldNameText(it) == name }
    }

    fun `test top-level unused local`() {
        assertTrue(JsonnetUnusedDeclarationUtil.isUnused(bind("local x = 1; 2", "x")))
    }

    fun `test top-level used local`() {
        assertFalse(JsonnetUnusedDeclarationUtil.isUnused(bind("local x = 1; x", "x")))
    }

    fun `test local used only by a sibling bind value is not unused`() {
        assertFalse(JsonnetUnusedDeclarationUtil.isUnused(bind("local a = 1, b = a; b", "a")))
    }

    fun `test object-scoped local unused`() {
        assertTrue(JsonnetUnusedDeclarationUtil.isUnused(bind("{ local secret = 'x', y: 1 }", "secret")))
    }

    fun `test object-scoped local used by a field value`() {
        assertFalse(JsonnetUnusedDeclarationUtil.isUnused(bind("{ local secret = 'x', y: secret }", "secret")))
    }

    fun `test unused local inside a function body`() {
        assertTrue(JsonnetUnusedDeclarationUtil.isUnused(bind("local f = function(x) local y = 1; x; f(1)", "y")))
    }

    fun `test visible field is never flagged regardless of usage`() {
        assertFalse(JsonnetUnusedDeclarationUtil.isUnusedHiddenField(field("{ a: 1 }", "a")))
    }

    fun `test unused hidden field`() {
        assertTrue(JsonnetUnusedDeclarationUtil.isUnusedHiddenField(field("{ helper:: 1, y: 1 }", "helper")))
    }

    fun `test hidden field used via self`() {
        assertFalse(JsonnetUnusedDeclarationUtil.isUnusedHiddenField(field("{ helper:: 1, y: self.helper }", "helper")))
    }

    fun `test hidden field used via dollar from a nested object`() {
        assertFalse(
            JsonnetUnusedDeclarationUtil.isUnusedHiddenField(
                field("{ helper:: 1, nested: { y: \$.helper } }", "helper"),
            ),
        )
    }

    fun `test hidden field unused even when a same-named field exists deeper in an unrelated object`() {
        val file = createPsiFile("test", "{ helper:: 1, nested: { helper: 2, y: self.helper } }")
        ensureParsed(file)
        val fields = PsiTreeUtil.collectElementsOfType(file, JsonnetField::class.java)
            .filter { JsonnetResolver.fieldNameText(it) == "helper" }
        val outerHidden = fields.single { JsonnetResolver.isHiddenField(it) }
        assertTrue(JsonnetUnusedDeclarationUtil.isUnusedHiddenField(outerHidden))
    }
}
