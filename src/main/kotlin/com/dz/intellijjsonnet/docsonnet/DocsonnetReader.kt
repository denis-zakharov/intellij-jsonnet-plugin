package com.dz.intellijjsonnet.docsonnet

import com.dz.intellijjsonnet.lang.JsonnetStringLiterals
import com.dz.intellijjsonnet.lang.psi.JsonnetArgList
import com.dz.intellijjsonnet.lang.psi.JsonnetArrayLiteral
import com.dz.intellijjsonnet.lang.psi.JsonnetCallSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.JsonnetNamedArg
import com.dz.intellijjsonnet.lang.psi.JsonnetObjectLiteral
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.lang.psi.reference.JsonnetResolver
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * What [docsonnet](https://github.com/jsonnet-libs/docsonnet) annotations say about a field: the `'#name'`
 * key next to `name` holds `d.fn(help, args)`, `d.obj(help)` or `d.val(type, help, default)`, with
 * `d.arg(name, type, default, enums)` for parameters (see `doc-util/main.libsonnet` for the definitions).
 */
sealed class DocsonnetDoc {
    /** Markdown, as written (after [DocsonnetReader]'s clean-up of generator quoting). */
    abstract val help: String?

    data class Fn(override val help: String?, val args: List<Arg>) : DocsonnetDoc()
    data class Obj(override val help: String?) : DocsonnetDoc()
    data class Val(val type: String?, override val help: String?, val default: String?) : DocsonnetDoc()

    /** [type] is the JSON type docsonnet would print (`d.T.integer` is `number`); [default] and [enums] are source text. */
    data class Arg(val name: String, val type: String?, val default: String?, val enums: String?)
}

/**
 * Reads docsonnet annotations straight from PSI, without evaluating anything: it works on unsaved edits, needs
 * no `doc-util` on the import path, and costs nothing for the (many thousand) annotations in a vendored k8s
 * library. The price is that only literal annotations are understood — a `help` that is computed (a variable,
 * `std.format`, ...) is simply absent, which the hover shows as "no description" rather than guessing.
 *
 * The receiver of `d.fn` isn't checked: docsonnet itself claims keys starting with `#`, so `'#x': anything.fn(..)`
 * is treated as its annotation whatever `d` is bound to.
 */
object DocsonnetReader {

    /**
     * The documentation [field]'s own `'#<name>'` sibling gives, or `null` when it has none in a shape we
     * understand. A sibling that only *modifies* an inherited docstring (`'#new'+: d.func.withArgs([...])`, as
     * k8s-libsonnet's `_custom/` files do) is applied to [base], the doc of whatever it overrides.
     */
    fun docFor(field: JsonnetField, base: DocsonnetDoc? = null): DocsonnetDoc? {
        val name = JsonnetResolver.fieldNameText(field)?.takeUnless { it.startsWith("#") } ?: return null
        val owner = PsiTreeUtil.getParentOfType(field, JsonnetObjectLiteral::class.java) ?: return null
        val annotation = JsonnetResolver.directFields(owner).firstOrNull { JsonnetResolver.fieldNameText(it) == "#$name" }
        return annotation?.expr?.let { read(it, base) }
    }

    /**
     * The docs of a field defined by several composed objects (`gen + custom`), given in composition order:
     * a later full docstring replaces an earlier one, a later modifier edits it.
     */
    fun effectiveDoc(definitions: List<JsonnetField>): DocsonnetDoc? =
        definitions.fold(null as DocsonnetDoc?) { doc, field -> docFor(field, doc) ?: doc }

    /** `a + b + c` of docstring pieces: the first is normally a call, the rest may be modifiers of the result so far. */
    internal fun read(annotation: JsonnetExpr, base: DocsonnetDoc? = null): DocsonnetDoc? {
        var doc = base
        var understood = false
        for (operand in operands(annotation)) {
            doc = apply(operand, doc)?.also { understood = true } ?: doc
        }
        return if (understood) doc else null
    }

    private fun operands(expr: JsonnetExpr): List<List<PsiElement>> {
        val result = mutableListOf(mutableListOf<PsiElement>())
        for (part in children(expr)) {
            if (part.node.elementType == JsonnetTypes.PLUS) result.add(mutableListOf()) else result.last().add(part)
        }
        return result
    }

    /**
     * One operand, shaped `receiver.a(args)` or `receiver.a.b(args)` — the flat Expr PSI makes those sibling
     * children: a name, `.suffix`es, a call.
     */
    private fun apply(parts: List<PsiElement>, current: DocsonnetDoc?): DocsonnetDoc? {
        if (parts.size < 3 || parts.first() !is JsonnetNameRef) return null
        val call = parts.last() as? JsonnetCallSuffix ?: return null
        val path = parts.subList(1, parts.size - 1).map { (it as? JsonnetDotSuffix)?.nameIdentifier?.text ?: return null }
        val args = call.argList
        return when (path) {
            listOf("fn") -> bind(args, listOf("help", "args")).let { DocsonnetDoc.Fn(help(it["help"]), readArgs(it["args"])) }
            listOf("obj") -> DocsonnetDoc.Obj(help(bind(args, listOf("help", "fields"))["help"]))
            listOf("val") -> bind(args, listOf("type", "help", "default")).let {
                DocsonnetDoc.Val(type(it["type"]), help(it["help"]), it["default"]?.let(::sourceText))
            }
            // `d.func.withHelp` / `withArgs` edit a function docstring; on anything else they mean nothing to us.
            listOf("func", "withHelp") -> functionOrEmpty(current)?.copy(help = help(bind(args, listOf("help"))["help"]))
            listOf("func", "withArgs") -> functionOrEmpty(current)?.copy(args = readArgs(bind(args, listOf("args"))["args"]))
            else -> null
        }
    }

    private fun functionOrEmpty(doc: DocsonnetDoc?): DocsonnetDoc.Fn? = when (doc) {
        null -> DocsonnetDoc.Fn(null, emptyList())
        is DocsonnetDoc.Fn -> doc
        else -> null
    }

    private fun readArgs(expr: JsonnetExpr?): List<DocsonnetDoc.Arg> {
        val literal = expr?.let(::children)?.singleOrNull() as? JsonnetArrayLiteral ?: return emptyList()
        return literal.arrayMemberList?.exprList.orEmpty().mapNotNull { element ->
            val call = docstringCall(element)?.takeIf { it.first == "arg" } ?: return@mapNotNull null
            val args = bind(call.second, listOf("name", "type", "default", "enums"))
            val name = args["name"]?.let(::staticString) ?: return@mapNotNull null
            DocsonnetDoc.Arg(name, type(args["type"]), args["default"]?.let(::sourceText), args["enums"]?.let(::sourceText))
        }
    }

    // -- arguments --

    private fun docstringCall(expr: JsonnetExpr): Pair<String, JsonnetArgList?>? {
        val parts = children(expr)
        if (parts.size != 3 || parts[0] !is JsonnetNameRef) return null
        val function = (parts[1] as? JsonnetDotSuffix)?.nameIdentifier?.text ?: return null
        return function to (parts[2] as? JsonnetCallSuffix ?: return null).argList
    }

    /** Positional arguments fill [parameters] in order; `name=value` ones go to their own slot. */
    private fun bind(args: JsonnetArgList?, parameters: List<String>): Map<String, JsonnetExpr> {
        val bound = HashMap<String, JsonnetExpr>()
        var position = 0
        for (child in args?.let(::children).orEmpty()) {
            when (child) {
                is JsonnetExpr -> parameters.getOrNull(position++)?.let { bound[it] = child }
                is JsonnetNamedArg -> {
                    val name = child.node.findChildByType(JsonnetTypes.IDENTIFIER)?.text
                    val value = child.expr
                    if (name != null && value != null) bound[name] = value
                }
            }
        }
        return bound
    }

    // -- values --

    private fun help(expr: JsonnetExpr?): String? = expr?.let(::staticString)?.let(::unquoteGeneratedHelp)?.takeIf { it.isNotBlank() }

    /** `'a'`, `"a" + 'b'`, `|||...|||` — string literals joined by `+`; anything computed is unknown. */
    internal fun staticString(expr: JsonnetExpr): String? {
        val out = StringBuilder()
        var expectString = true
        for (part in children(expr)) {
            if (expectString) {
                if (part.node.elementType != JsonnetTypes.STRING) return null
                out.append(JsonnetStringLiterals.decode(part.text) ?: return null)
            } else if (part.node.elementType != JsonnetTypes.PLUS) return null
            expectString = !expectString
        }
        return if (expectString) null else out.toString()
    }

    /** `d.T.integer` → `number`, mirroring `doc-util`'s own table; a plain string literal is taken as is. */
    private fun type(expr: JsonnetExpr?): String? {
        expr ?: return null
        staticString(expr)?.let { return it }
        val parts = children(expr)
        val suffixes = parts.drop(1).filterIsInstance<JsonnetDotSuffix>().mapNotNull { it.nameIdentifier?.text }
        if (parts.firstOrNull() !is JsonnetNameRef || parts.size != suffixes.size + 1 || suffixes.size < 2) return null
        if (suffixes[suffixes.size - 2] != "T") return null
        return TYPES[suffixes.last()] ?: suffixes.last()
    }

    private val TYPES = mapOf(
        "string" to "string", "number" to "number", "int" to "number", "integer" to "number",
        "boolean" to "bool", "bool" to "bool", "object" to "object", "array" to "array", "any" to "any",
        "null" to "null", "nil" to "null", "func" to "function", "function" to "function",
    )

    /** Source text, whitespace collapsed and cut short: defaults like a `['sh', '-c', ...]` array aren't worth a paragraph. */
    private fun sourceText(expr: JsonnetExpr): String {
        val text = expr.text.replace(Regex("\\s+"), " ").trim()
        return if (text.length > MAX_SOURCE_TEXT) text.take(MAX_SOURCE_TEXT - 1) + "…" else text
    }

    private const val MAX_SOURCE_TEXT = 80

    /**
     * k8s-libsonnet's generator writes descriptions as JSON strings: `'"Annotations is ... resource."\n\n**Note:** ...'`,
     * i.e. the help itself starts with a quote and has `\n` and `\"` still escaped inside. Unwrap that so the
     * markdown reads as prose. Only when the closing quote ends the text or a line — a help that merely starts
     * with a quoted word is left alone.
     */
    internal fun unquoteGeneratedHelp(text: String): String {
        if (!text.startsWith("\"")) return text
        val body = StringBuilder()
        var i = 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    when (val e = text[i + 1]) {
                        'n' -> body.append('\n')
                        't' -> body.append('\t')
                        'r' -> body.append('\r')
                        '"', '\\', '/' -> body.append(e)
                        else -> body.append(c).append(e)
                    }
                    i += 2
                }
                c == '"' -> {
                    val rest = text.substring(i + 1)
                    return if (rest.isEmpty() || rest.startsWith("\n")) body.toString() + rest else text
                }
                else -> {
                    body.append(c)
                    i++
                }
            }
        }
        return text
    }

    /** Children that carry meaning: no whitespace, comments or commas' worth of trivia. */
    private fun children(element: PsiElement): List<PsiElement> =
        generateSequence(element.firstChild) { it.nextSibling }
            .filter { it !is PsiWhiteSpace && it !is PsiComment && it.node.elementType != JsonnetTypes.COMMA }
            .toList()
}
