package com.dz.intellijjsonnet.engine.extension

import com.dz.intellijjsonnet.shaded.scala.collection.immutable.Map as ScalaMap
import com.dz.intellijjsonnet.shaded.sjsonnet.Eval
import com.dz.intellijjsonnet.shaded.sjsonnet.EvalScope
import com.dz.intellijjsonnet.shaded.sjsonnet.`Error$`
import com.dz.intellijjsonnet.shaded.sjsonnet.Position
import com.dz.intellijjsonnet.shaded.sjsonnet.`TailstrictModeDisabled$`
import com.dz.intellijjsonnet.shaded.sjsonnet.Val
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.security.MessageDigest

/**
 * The functions Tanka's Go runtime injects into its go-jsonnet VM, reachable as `std.native('name')`.
 * Only the pure ones can live on the JVM: `helmTemplate`/`kustomizeBuild` shell out to external
 * binaries and stay a ground-truth-tier (`tk`) concern, so `std.native('helmTemplate')` is still
 * `null` here — exactly what plain Jsonnet does with an unregistered native.
 *
 * Names, parameter names and semantics follow `pkg/jsonnet/native/funcs.go` in grafana/tanka, and the
 * outputs were checked against a real `tk eval` (see `TankaNativesTest`). Regexes are RE2 on both
 * sides: sjsonnet already embeds RE2/J, a port of the very engine Go's `regexp` package implements.
 */
internal object TankaNatives {

    private val tail = `TailstrictModeDisabled$`.`MODULE$`

    fun build(stock: ScalaMap<String, Val.Func>): ScalaMap<String, Val.Func> {
        val parseJsonBuiltin = stock.apply("parseJson")
        return SjsonnetExtensions.scalaMapOf(
            mapOf(
                "parseJson" to builtin1("parseJson", "json") { text, ev, pos ->
                    parseJsonBuiltin.apply1(text, pos, ev, tail)
                },
                "parseYaml" to builtin1("parseYaml", "yaml") { text, ev, pos ->
                    parseYaml(text.value().asString(), parseJsonBuiltin, ev, pos)
                },
                "manifestJsonFromJson" to builtin2("manifestJsonFromJson", "json", "indent") { json, indent, _, pos ->
                    val text = json.value().asString().trim()
                    Val.Str(pos, guarded("manifestJsonFromJson") { GoFormats.jsonIndent(text, indent.value().asInt()) } + "\n")
                },
                "manifestYamlFromJson" to builtin1("manifestYamlFromJson", "json") { json, _, pos ->
                    Val.Str(pos, guarded("manifestYamlFromJson") { GoFormats.yamlMarshal(json.value().asString()) })
                },
                "escapeStringRegex" to builtin1("escapeStringRegex", "str") { s, _, pos ->
                    Val.Str(pos, GoFormats.quoteMeta(s.value().asString()))
                },
                "regexMatch" to builtin2("regexMatch", "regex", "string") { regex, string, _, pos ->
                    val pattern = compile(regex.value().asString())
                    Val.bool(pos, pattern.matcher(string.value().asString()).find())
                },
                "regexSubst" to builtin3("regexSubst", "regex", "src", "repl") { regex, src, repl, _, pos ->
                    Val.Str(pos, replaceAll(compile(regex.value().asString()), src.value().asString(), repl.value().asString()))
                },
                "sha256" to builtin1("sha256", "str") { s, _, pos ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(s.value().asString().toByteArray(Charsets.UTF_8))
                    Val.Str(pos, digest.joinToString("") { "%02x".format(it) })
                },
            ),
        )
    }

    /** Tanka's `parseYaml` always yields an array of documents, unlike `std.parseYaml`, which unwraps a lone one. */
    private fun parseYaml(text: String, parseJson: Val.Func, ev: EvalScope, pos: Position): Val {
        val json = try {
            GoFormats.yamlDocumentsAsJson(text)
        } catch (e: Exception) {
            fail("parsing yaml: ${e.message}")
        }
        return parseJson.apply1(Val.Str(pos, json), pos, ev, tail)
    }

    private fun compile(regex: String): Pattern = try {
        Pattern.compile(regex)
    } catch (e: PatternSyntaxException) {
        fail("error parsing regexp: ${e.description}: `${e.pattern}`")
    }

    private fun replaceAll(pattern: Pattern, src: String, template: String): String {
        val matcher = pattern.matcher(src)
        val groups = pattern.namedGroups()
        val out = StringBuilder()
        var last = 0
        while (matcher.find()) {
            out.append(src, last, matcher.start())
            out.append(GoFormats.expandTemplate(template, matcher, groups))
            last = matcher.end()
        }
        return out.append(src, last, src.length).toString()
    }

    private inline fun <T> guarded(what: String, block: () -> T): T = try {
        block()
    } catch (e: com.dz.intellijjsonnet.shaded.sjsonnet.Error) {
        throw e
    } catch (e: Exception) {
        fail("$what: ${e.message}")
    }

    /** `Error.fail` always throws, but its Scala `Nothing$` return type isn't Kotlin's `Nothing`. */
    private fun fail(message: String): Nothing {
        `Error$`.`MODULE$`.fail(message)
        throw IllegalStateException("unreachable")
    }

    private fun builtin1(name: String, p1: String, body: (Eval, EvalScope, Position) -> Val): Val.Func =
        object : Val.Builtin1(name, p1, null) {
            override fun evalRhs(arg1: Eval, ev: EvalScope, pos: Position): Val = body(arg1, ev, pos)
        }

    private fun builtin2(name: String, p1: String, p2: String, body: (Eval, Eval, EvalScope, Position) -> Val): Val.Func =
        object : Val.Builtin2(name, p1, p2, null) {
            override fun evalRhs(arg1: Eval, arg2: Eval, ev: EvalScope, pos: Position): Val = body(arg1, arg2, ev, pos)
        }

    private fun builtin3(name: String, p1: String, p2: String, p3: String, body: (Eval, Eval, Eval, EvalScope, Position) -> Val): Val.Func =
        object : Val.Builtin3(name, p1, p2, p3, null) {
            override fun evalRhs(arg1: Eval, arg2: Eval, arg3: Eval, ev: EvalScope, pos: Position): Val = body(arg1, arg2, arg3, ev, pos)
        }
}
