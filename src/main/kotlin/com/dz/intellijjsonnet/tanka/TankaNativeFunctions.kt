package com.dz.intellijjsonnet.tanka

import com.dz.intellijjsonnet.lang.psi.JsonnetArgList
import com.dz.intellijjsonnet.lang.psi.JsonnetCallSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil

/**
 * `std.native('name')` calls out to a function Tanka's Go runtime injects into
 * its `go-jsonnet` VM (`parseYaml`, `manifestJsonFromJson`, ...) — there's no
 * such thing at the vanilla-Jsonnet level. This registry drives completion and
 * hover for the function-name string; the fast tier *evaluates* every entry
 * here with a JVM reimplementation (see `engine/extension/TankaNatives.kt`,
 * which `TankaNativesTest` keeps in step with this list). `helmTemplate` and
 * `kustomizeBuild` shell out to external binaries even in real Tanka, so they
 * are deliberately absent: `std.native` returns `null` for them in the preview.
 */
object TankaNativeFunctions {

    data class Entry(val name: String, val description: String)

    val entries: List<Entry> = listOf(
        Entry("parseJson", "Parses a JSON string into a Jsonnet value."),
        Entry("parseYaml", "Parses a (possibly multi-document) YAML string into an array of Jsonnet values."),
        Entry("manifestJsonFromJson", "Re-serializes a JSON string with the given indentation."),
        Entry("manifestYamlFromJson", "Converts a JSON string into a YAML document string."),
        Entry("escapeStringRegex", "Escapes a string for safe use inside a regular expression."),
        Entry("regexMatch", "Tests whether a string matches a regular expression."),
        Entry("regexSubst", "Replaces regular-expression matches in a string."),
        Entry("sha256", "Returns the hex-encoded SHA-256 digest of a string."),
    )

    val names: List<String> = entries.map { it.name }

    fun describe(name: String): String? = entries.firstOrNull { it.name == name }?.description

    /** True when [stringLiteral] is the sole positional argument of a `std.native(...)` call. */
    fun isNativeNameArgument(stringLiteral: PsiElement): Boolean {
        val argExpr = stringLiteral.parent as? JsonnetExpr ?: return false
        val argList = argExpr.parent as? JsonnetArgList ?: return false
        if (PsiTreeUtil.getChildrenOfType(argList, JsonnetExpr::class.java)?.size != 1) return false
        val callSuffix = argList.parent as? JsonnetCallSuffix ?: return false
        return isNativeReceiver(callSuffix)
    }

    private fun isNativeReceiver(callSuffix: JsonnetCallSuffix): Boolean {
        val exprParent = callSuffix.parent as? JsonnetExpr ?: return false
        val dotSuffix = prevNonWhitespaceSibling(callSuffix) as? JsonnetDotSuffix ?: return false
        if (dotSuffix.parent != exprParent || dotSuffix.text.trimStart('.') != "native") return false
        val receiver = prevNonWhitespaceSibling(dotSuffix) as? JsonnetNameRef ?: return false
        return receiver.parent == exprParent && receiver.text == "std"
    }

    private fun prevNonWhitespaceSibling(element: PsiElement): PsiElement? {
        var prev = element.prevSibling
        while (prev != null && prev.node.elementType == TokenType.WHITE_SPACE) {
            prev = prev.prevSibling
        }
        return prev
    }
}
