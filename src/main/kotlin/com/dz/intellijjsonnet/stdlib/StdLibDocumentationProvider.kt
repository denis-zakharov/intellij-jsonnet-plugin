package com.dz.intellijjsonnet.stdlib

import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.tanka.TankaNativeFunctions
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Quick-doc for `std.<name>` and `std.native('<name>')` — see [StdLibRegistry] / [TankaNativeFunctions]. */
class StdLibDocumentationProvider : AbstractDocumentationProvider() {
    // A string literal resolves to nothing, so without this the platform finds no hover target on it at all.
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? =
        contextElement?.takeIf { it.node?.elementType == JsonnetTypes.STRING && TankaNativeFunctions.isNativeNameArgument(it) }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = originalElement ?: element ?: return null

        if (target.node?.elementType == JsonnetTypes.STRING && TankaNativeFunctions.isNativeNameArgument(target)) {
            val name = target.text.trim('\'', '"')
            val description = TankaNativeFunctions.describe(name) ?: return null
            return "<b>std.native('$name')</b><br/>$description" +
                "<br/><i>Injected by Tanka's Go runtime; the in-editor preview evaluates it with a JVM reimplementation.</i>"
        }

        val dotSuffix = target.parent as? JsonnetDotSuffix ?: return null
        if (!StdLibRegistry.isStdMemberAccess(dotSuffix)) return null
        val name = dotSuffix.nameIdentifier?.text ?: return null
        if (name !in StdLibRegistry.memberNames) return null
        return stdMemberDoc(target.project, name)
    }

    /**
     * Signature (from the engine), the official description ([StdLibDocs]), then a usage snippet and its result
     * ([StdLibExamples]). Any part can be missing: the reference doesn't describe every function.
     */
    private fun stdMemberDoc(project: Project, name: String): String {
        val parameters = StdLibRegistry.parameters(name)
        val signature = if (parameters == null) "std.$name" else
            "std.$name(" + parameters.joinToString(", ") { if (it.optional) "[${it.name}]" else it.name } + ")"
        val docs = StdLibDocs.forName(name)
        val example = StdLibExamples.forName(name)

        val html = StringBuilder()
        html.append(DocumentationMarkup.DEFINITION_START).append(escapeXmlEntities(signature)).append(DocumentationMarkup.DEFINITION_END)
        if (docs != null) html.append(DocumentationMarkup.CONTENT_START).append(docs.html).append(DocumentationMarkup.CONTENT_END)
        if (docs == null && example == null) {
            return html.append(DocumentationMarkup.CONTENT_START).append("Jsonnet standard library function.")
                .append(DocumentationMarkup.CONTENT_END).toString()
        }

        html.append(DocumentationMarkup.SECTIONS_START)
        if (example != null) {
            section(html, "Example") {
                val snippet = if (example.result == null) example.code else "${example.code}\n// => ${example.result}"
                append("<pre>")
                HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
                    this, project, JsonnetLanguage, snippet, true, DocumentationSettings.getHighlightingSaturation(false),
                )
                append("</pre>")
                example.note?.let { append("<i>").append(markdownCode(escapeXmlEntities(it))).append("</i>") }
            }
        }
        if (docs != null) {
            docs.since?.let { section(html, "Since") { append("Jsonnet ").append(escapeXmlEntities(it)) } }
            // CC BY 2.5 attribution for the description above.
            section(html, "Source") {
                append("<a href=\"").append(docs.url).append("\">Jsonnet standard library reference</a> (CC BY 2.5)")
            }
        }
        return html.append(DocumentationMarkup.SECTIONS_END).toString()
    }

    private fun section(html: StringBuilder, header: String, content: StringBuilder.() -> Unit) {
        html.append(DocumentationMarkup.SECTION_HEADER_START).append(header).append(':')
            .append(DocumentationMarkup.SECTION_SEPARATOR)
        html.content()
        html.append(DocumentationMarkup.SECTION_END).append("</tr>")
    }

    private fun markdownCode(escaped: String): String = escaped.replace(Regex("`([^`]+)`"), "<code>$1</code>")
}
