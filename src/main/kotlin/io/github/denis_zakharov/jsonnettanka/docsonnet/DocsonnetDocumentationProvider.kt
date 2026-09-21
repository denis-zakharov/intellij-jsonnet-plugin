package io.github.denis_zakharov.jsonnettanka.docsonnet

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference

/**
 * Quick-doc for fields documented with [docsonnet](https://github.com/jsonnet-libs/docsonnet) — the
 * `'#withPort':: d.fn(...)` key next to `withPort(...)::`, which is how Tanka libraries (k8s-libsonnet,
 * xtd, your own `lib/`) describe themselves. The platform hands us the field a reference resolves to, or the
 * field itself when hovering its declaration, so this works at call sites and in the library alike.
 * See [DocsonnetReader] for what is understood.
 */
class DocsonnetDocumentationProvider : AbstractDocumentationProvider() {

    /**
     * `k.apps.v1.deployment.new` resolves to *two* fields — the generated `new(name)` and the hand-written
     * `new(name, replicas, containers)` composed over it — and the platform shows nothing for an ambiguous
     * reference. A later definition overrides an earlier one, so hand over the last one that has docs (its
     * parameter list is the effective signature); [generateDoc] then folds the earlier ones in. A single
     * target is left to the platform.
     */
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        val targets = definitionsAt(contextElement)
        return if (targets.size < 2) null else targets.lastOrNull { DocsonnetReader.docFor(it) != null }
    }

    /** The fields `receiver.name` resolves to, in composition order; empty when [leaf] isn't such a `name`. */
    private fun definitionsAt(leaf: PsiElement?): List<JsonnetField> {
        if (leaf?.node?.elementType != JsonnetTypes.IDENTIFIER) return emptyList()
        val suffix = leaf.parent as? JsonnetDotSuffix ?: return emptyList()
        return (suffix.reference as? PsiPolyVariantReference)?.multiResolve(false)
            ?.mapNotNull { it.element as? JsonnetField }.orEmpty()
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val field = element as? JsonnetField ?: return null
        val name = JsonnetResolver.fieldNameText(field) ?: return null
        // `deployment.new` is `gen`'s docstring with `_custom`'s modifier on top: fold everything up to the definition shown.
        val chain = definitionsAt(originalElement).let { all -> if (field in all) all.take(all.indexOf(field) + 1) else listOf(field) }
        val doc = DocsonnetReader.effectiveDoc(chain) ?: return null
        if (doc.isEmpty()) return null

        val html = StringBuilder()
        html.append(DocumentationMarkup.DEFINITION_START).append(escapeXmlEntities(signature(field, name, doc))).append(DocumentationMarkup.DEFINITION_END)
        doc.help?.let {
            html.append(DocumentationMarkup.CONTENT_START)
                .append(DocMarkdownToHtmlConverter.convert(field.project, it, JsonnetLanguage))
                .append(DocumentationMarkup.CONTENT_END)
        }
        if (doc is DocsonnetDoc.Fn && doc.args.isNotEmpty()) {
            html.append(DocumentationMarkup.SECTIONS_START)
                .append(DocumentationMarkup.SECTION_HEADER_START).append("Params:").append(DocumentationMarkup.SECTION_SEPARATOR)
            html.append(doc.args.joinToString("<br/>") { describe(it) })
            html.append(DocumentationMarkup.SECTION_END).append("</tr>").append(DocumentationMarkup.SECTIONS_END)
        }
        return html.toString()
    }

    private fun DocsonnetDoc.isEmpty(): Boolean = when (this) {
        is DocsonnetDoc.Fn -> help == null && args.isEmpty()
        is DocsonnetDoc.Obj -> help == null
        is DocsonnetDoc.Val -> help == null && type == null
    }

    /** In docsonnet's own vocabulary (`fn`/`obj`/`val`), which also tells the reader where the text came from. */
    private fun signature(field: JsonnetField, name: String, doc: DocsonnetDoc): String = when (doc) {
        is DocsonnetDoc.Fn -> "fn " + name + (field.paramList?.text?.let(::oneLine)
            ?: doc.args.joinToString(", ", "(", ")") { it.name + (it.default?.let { d -> "=$d" } ?: "") })
        is DocsonnetDoc.Obj -> "obj $name"
        is DocsonnetDoc.Val -> "val $name" + (doc.type?.let { ": $it" } ?: "") + (doc.default?.let { " = $it" } ?: "")
    }

    /** A parameter list as written across lines — `(\n  a,\n  b,\n)` — read as `(a, b)`. */
    private fun oneLine(paramList: String): String =
        paramList.replace(Regex("\\s+"), " ").replace("( ", "(").replace(Regex(",? \\)"), ")")

    private fun describe(arg: DocsonnetDoc.Arg): String = buildString {
        append("<code>").append(escapeXmlEntities(arg.name)).append("</code>")
        arg.type?.let { append(" <i>").append(escapeXmlEntities(it)).append("</i>") }
        arg.default?.let { append(", default <code>").append(escapeXmlEntities(it)).append("</code>") }
        arg.enums?.let { append(", one of <code>").append(escapeXmlEntities(it)).append("</code>") }
    }
}
