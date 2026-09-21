package io.github.denis_zakharov.jsonnettanka.editor

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetParserDefinition
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetCallSuffix
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase

class JsonnetInlayParameterHintsProviderTest : ParsingTestCase("", "jsonnet", JsonnetParserDefinition()) {

    override fun getTestDataPath(): String = "."
    override fun skipSpaces(): Boolean = true

    private val provider = JsonnetInlayParameterHintsProvider()

    private fun callSuffixes(text: String): List<JsonnetCallSuffix> {
        val file = createPsiFile("test", text)
        ensureParsed(file)
        return PsiTreeUtil.collectElementsOfType(file, JsonnetCallSuffix::class.java).toList()
    }

    fun `test positional args get param name hints for a local function`() {
        val call = callSuffixes("local add(x, y) = x + y; add(1, 2)").last()
        val hints = provider.getParameterHints(call)
        assertEquals(listOf("x:", "y:"), hints.map { it.text })
    }

    fun `test named arg is skipped but still consumes its position`() {
        val call = callSuffixes("local add(x, y) = x + y; add(1, y=2)").last()
        val hints = provider.getParameterHints(call)
        assertEquals(listOf("x:"), hints.map { it.text })
    }

    fun `test hint suppressed when the argument text already matches the param name`() {
        val call = callSuffixes("local add(x, y) = x + y; local x = 1; add(x, 2)").last()
        val hints = provider.getParameterHints(call)
        assertEquals(listOf("y:"), hints.map { it.text })
    }

    fun `test method-style call through self resolves params too`() {
        val call = callSuffixes("{ greet(name):: 'hi ' + name, x: self.greet('a') }").last()
        val hints = provider.getParameterHints(call)
        assertEquals(listOf("name:"), hints.map { it.text })
    }

    fun `test unresolved callee yields no hints`() {
        val call = callSuffixes("unknownFn(1, 2)").single()
        assertTrue(provider.getParameterHints(call).isEmpty())
    }

    fun `test call to a value with no params yields no hints`() {
        val call = callSuffixes("local f = 1; f(1)").last()
        assertTrue(provider.getParameterHints(call).isEmpty())
    }
}
