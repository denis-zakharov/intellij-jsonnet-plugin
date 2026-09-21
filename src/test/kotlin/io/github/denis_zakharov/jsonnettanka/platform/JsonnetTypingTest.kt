package io.github.denis_zakharov.jsonnettanka.platform

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Enter and typed closers through the real editor typing path (`myFixture.type`), i.e. the Block model's
 * `getChildAttributes`/`getIndent`. The expected indents are what `jsonnetfmt` would produce for the finished code
 * (`JsonnetTypingConsistencyTest` checks that on whole files; these are the *incomplete* constructs it can't reach).
 */
class JsonnetTypingTest : BasePlatformTestCase() {
    // Compared as plain strings (with the caret spelled out) so a failure prints both texts.
    private fun current(): String =
        StringBuilder(myFixture.editor.document.text).insert(myFixture.caretOffset, "<caret>").toString()

    private fun enter(before: String, after: String) {
        myFixture.configureByText("a.jsonnet", before)
        myFixture.type('\n')
        assertEquals(after, current())
    }

    private fun typeChar(before: String, c: Char, after: String) {
        myFixture.configureByText("a.jsonnet", before)
        myFixture.type(c)
        assertEquals(after, current())
    }

    fun `test Enter after a comma in a hanging object lines up with the first field`() =
        enter("{ a: 1,<caret>", "{ a: 1,\n  <caret>")

    fun `test Enter after a comma in an object that starts on the next line`() =
        enter("{\n  a: 1,<caret>\n}", "{\n  a: 1,\n  <caret>\n}")

    fun `test Enter after an opening brace indents one level`() =
        enter("local x = {<caret>}", "local x = {\n  <caret>\n}")

    fun `test Enter after an opening bracket indents one level`() =
        enter("{\n  a: [<caret>\n}", "{\n  a: [\n    <caret>\n}")

    fun `test Enter after a comma in a hanging array lines up with the first element`() =
        enter("[1,<caret>", "[1,\n <caret>")

    fun `test Enter after a comma in call arguments lines up with the first argument`() =
        enter("f(a,<caret>", "f(a,\n  <caret>")

    fun `test Enter after an opening paren indents one level`() =
        enter("f(<caret>", "f(\n  <caret>")

    fun `test Enter after a comma in parameters lines up`() =
        enter("local f(x,<caret>", "local f(x,\n        <caret>")

    fun `test Enter after a bind's equals sign indents one level`() =
        enter("local x =<caret>", "local x =\n  <caret>")

    fun `test Enter after a completed local returns to the body indent`() =
        enter("local x = 1;<caret>", "local x = 1;\n<caret>")

    fun `test Enter after a comma between binds lines up with the first bind`() =
        enter("local a = 1,<caret>", "local a = 1,\n      <caret>")

    fun `test Enter after a field colon indents the value`() =
        enter("{\n  a:<caret>\n}", "{\n  a:\n    <caret>\n}")

    fun `test Enter after then indents the branch`() =
        enter("local x = if a then<caret>", "local x = if a then\n  <caret>")

    fun `test Enter after else indents the branch`() =
        enter("if a then b else<caret>", "if a then b else\n  <caret>")

    fun `test Enter after a binary operator lines up with the first operand`() =
        enter("local x = a +<caret>", "local x = a +\n          <caret>")

    fun `test Enter after an operator at the start of the file`() =
        enter("a +<caret>", "a +\n<caret>")

    fun `test Enter between braces puts the closer on its own line`() =
        enter("{<caret>}", "{\n  <caret>\n}")

    fun `test Enter between brackets puts the closer on its own line`() =
        enter("[<caret>]", "[\n  <caret>\n]")

    fun `test Enter after a line comment keeps the indent`() =
        enter("{\n  a: 1,\n  // note<caret>\n}", "{\n  a: 1,\n  // note\n  <caret>\n}")

    fun `test Enter after a trailing comment starts the next field`() =
        enter("{\n  a: 1,  // note<caret>\n}", "{\n  a: 1,  // note\n  <caret>\n}")

    fun `test Enter inside a text block keeps the previous line's indent`() =
        enter("{\n  a: |||\n    text<caret>\n  |||,\n}", "{\n  a: |||\n    text\n    <caret>\n  |||,\n}")

    fun `test Enter inside a nested call keeps the argument alignment`() =
        enter("local x = std.foo(a, g(b,<caret>", "local x = std.foo(a, g(b,\n                       <caret>")

    fun `test a typed closing brace goes back to the opener's indent`() =
        typeChar("{\n  a: {\n    b: 1,\n    <caret>\n}", '}', "{\n  a: {\n    b: 1,\n  }<caret>\n}")

    fun `test a typed closing bracket goes back to the opener's indent`() =
        typeChar("{\n  a: [\n    1,\n    <caret>\n}", ']', "{\n  a: [\n    1,\n  ]<caret>\n}")

    fun `test a typed closing paren goes back to the line of the call`() =
        typeChar("local x = f(\n  a,\n  <caret>", ')', "local x = f(\n  a,\n)<caret>")

    fun `test a single-line call is untouched by a typed closing paren`() =
        typeChar("local x = f(a, b<caret>", ')', "local x = f(a, b)<caret>")

    fun `test a closing bracket typed inside a string is left alone`() =
        typeChar("{\n  a: |||\n    text\n    <caret>\n  |||,\n}", ']', "{\n  a: |||\n    text\n    ]<caret>\n  |||,\n}")

    // --- spacing: the Block model is also what formats a file with syntax errors, so check it against jsonnetfmt's ---

    private fun blockFormat(text: String): String {
        myFixture.configureByText("a.jsonnet", text)
        assertTrue("fixture must have a syntax error to reach the Block model", com.intellij.psi.util.PsiTreeUtil.hasErrorElements(myFixture.file))
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        return myFixture.editor.document.text
    }

    fun `test spacing follows jsonnetfmt`() {
        val input = "local a=1,b=f( x,y );{a:1,b:[1,2],c:f( x,y )+3*2,d:!a,e:a.b[0], f( x=1 ):: x,local y=1,g:if a then b else c,h:a[1:2],i:-1}\n)"
        assertEquals(
            "local a = 1, b = f(x, y); { a: 1, b: [1, 2], c: f(x, y) + 3 * 2, d: !a, e: a.b[0], f(x=1):: x, local y = 1, g: if a then b else c, h: a[1:2], i: -1 }\n)",
            blockFormat(input),
        )
    }

    fun `test spacing keeps line breaks and honours the padding options`() {
        val out = blockFormat("{\n  a:[1,2],\n  b:{c:1}}\n)")
        assertTrue(out, out.contains("a: [1, 2],"))
        assertTrue(out, out.contains("b: { c: 1 }"))
    }

    // --- item 22: quotes and comments ---

    fun `test a typed single quote is closed`() = typeChar("{ a: <caret> }", '\'', "{ a: '<caret>' }")

    fun `test a typed double quote is closed`() = typeChar("{ a: <caret> }", '"', "{ a: \"<caret>\" }")

    fun `test a closing quote is typed over`() = typeChar("{ a: 'x<caret>' }", '\'', "{ a: 'x'<caret> }")

    fun `test a quote after a word character is not paired`() = typeChar("{ a: x<caret> }", '\'', "{ a: x'<caret> }")

    fun `test a quote typed in a comment is not paired`() = typeChar("// it<caret>", '\'', "// it'<caret>")

    fun `test a single quote typed inside a double-quoted string is not paired`() =
        typeChar("{ a: \"it<caret>\" }", '\'', "{ a: \"it'<caret>\" }")

    fun `test Backspace on an opening quote removes the pair`() {
        myFixture.configureByText("a.jsonnet", "{ a: '<caret>' }")
        myFixture.type('\b')
        assertEquals("{ a: <caret> }", current())
    }

    fun `test Enter in a line comment continues it`() =
        enter("// first<caret> second", "// first\n// <caret>second")

    fun `test Enter at the end of a line comment does not add a prefix`() =
        enter("// first<caret>", "// first\n<caret>")

    fun `test Enter in a hash comment continues it with a hash`() =
        enter("# first<caret> second", "# first\n# <caret>second")

    fun `test Enter in a block comment is left to the platform`() =
        enter("/* first<caret> // second */", "/* first\n <caret>// second */")

    fun `test Enter in a trailing comment continues it`() =
        enter("{ a: 1, // first<caret>second\n}", "{ a: 1, // first\n  // <caret>second\n}")

    fun `test the same quotes work in a libsonnet file`() {
        myFixture.configureByText("a.libsonnet", "{ a: <caret> }")
        myFixture.type('\'')
        assertEquals("{ a: '<caret>' }", current())
    }
}
