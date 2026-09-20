package com.dz.intellijjsonnet.lang.psi.reference

import com.dz.intellijjsonnet.lang.psi.JsonnetAssertExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetBind
import com.dz.intellijjsonnet.lang.psi.JsonnetCallSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetField
import com.dz.intellijjsonnet.lang.psi.JsonnetFile
import com.dz.intellijjsonnet.lang.psi.JsonnetFunctionExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetIfExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetImportExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetIndexSuffix
import com.dz.intellijjsonnet.lang.psi.JsonnetLocalExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.dz.intellijjsonnet.lang.psi.JsonnetObjectLiteral
import com.dz.intellijjsonnet.lang.psi.JsonnetParenExpr
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.psi.nameIdentifier
import com.dz.intellijjsonnet.tanka.TankaJpath
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil

/**
 * What an expression can statically be, as far as reading the PSI can tell: the object
 * literals whose fields it has (several after `a + b` / `a { ... }`), and the function
 * bodies it would run if called. Deliberately not a type system — anything that needs
 * real evaluation (function parameters, `std.*`, computed fields, arithmetic) is simply
 * empty, which callers treat as "unknown", never as "no such member".
 */
class JsonnetStaticValue(
    val objects: List<JsonnetObjectLiteral> = emptyList(),
    /** [JsonnetBind] / [JsonnetField] with parameters, or a [JsonnetFunctionExpr]. */
    val callables: List<PsiElement> = emptyList(),
) {
    val isEmpty: Boolean get() = objects.isEmpty() && callables.isEmpty()

    operator fun plus(other: JsonnetStaticValue): JsonnetStaticValue = when {
        other.isEmpty -> this
        isEmpty -> other
        else -> JsonnetStaticValue((objects + other.objects).distinct(), (callables + other.callables).distinct())
    }

    /** Fields declared directly in any of [objects], in source order per object. */
    val fields: List<JsonnetField> get() = objects.flatMap { JsonnetResolver.directFields(it) }

    fun fieldsNamed(name: String): List<JsonnetField> = fields.filter { JsonnetResolver.fieldNameText(it) == name }

    companion object {
        val EMPTY = JsonnetStaticValue()
    }
}

/**
 * Resolves member access chains (`lib.util.helper`, `self.x`, `super.x`, `deployment.new(...).spec`)
 * by walking the PSI: through `local` bindings, `import`s (using [TankaJpath], same as evaluation),
 * `+` / `{ ... }` object composition, and calls of functions defined in source.
 *
 * The Expr PSI is flat — `a.b(c) + d.e` is one `Expr` whose children are `a`, `.b`, `(c)`, `+`, `d`, `.e`
 * — so the receiver of a suffix is "the atom and suffixes of its operand, up to it" (see [Operand]).
 */
object JsonnetStaticValues {

    private const val MAX_DEPTH = 40

    /** The value whose members [dotSuffix] selects from — what `.<caret>` completes against. */
    fun receiverOf(dotSuffix: JsonnetDotSuffix): JsonnetStaticValue {
        val expr = dotSuffix.parent as? JsonnetExpr ?: return JsonnetStaticValue.EMPTY
        val (operands, _) = split(expr)
        for (operand in operands) {
            val index = operand.elements.indexOf(dotSuffix)
            if (index > 0) return Session().evalOperand(operand, upTo = index)
        }
        return JsonnetStaticValue.EMPTY
    }

    private class Operand(val elements: List<PsiElement>)

    private val BINARY_OPS = TokenSet.create(
        JsonnetTypes.OROR, JsonnetTypes.ANDAND, JsonnetTypes.PIPE, JsonnetTypes.CARET, JsonnetTypes.AMP,
        JsonnetTypes.EQEQ, JsonnetTypes.NEQ, JsonnetTypes.LTE, JsonnetTypes.GTE, JsonnetTypes.SHL,
        JsonnetTypes.SHR, JsonnetTypes.LT, JsonnetTypes.GT, JsonnetTypes.IN_KW, JsonnetTypes.PLUS,
        JsonnetTypes.MINUS, JsonnetTypes.STAR, JsonnetTypes.SLASH, JsonnetTypes.PERCENT,
    )

    private val PLUS_FIELD_OPS = TokenSet.create(
        JsonnetTypes.PLUSCOLON, JsonnetTypes.PLUSCOLONCOLON, JsonnetTypes.PLUSCOLONCOLONCOLON,
    )

    private fun isSuffix(element: PsiElement) =
        element is JsonnetDotSuffix || element is JsonnetIndexSuffix || element is JsonnetCallSuffix

    /** Splits a flat Expr into its operands (an atom plus the suffixes that follow it) and the operators between them. */
    private fun split(expr: JsonnetExpr): Pair<List<Operand>, List<IElementType>> {
        val operands = mutableListOf<Operand>()
        val operators = mutableListOf<IElementType>()
        var current = mutableListOf<PsiElement>()
        var child: PsiElement? = expr.firstChild
        while (child != null) {
            val type = child.node.elementType
            when {
                child is PsiWhiteSpace || child is PsiComment || child is PsiErrorElement -> {}
                type in BINARY_OPS -> {
                    if (current.isNotEmpty()) operands.add(Operand(current))
                    current = mutableListOf()
                    operators.add(type)
                }
                // `x { ... }` is `x + { ... }`: an object literal after an atom is a suffix.
                current.isEmpty() -> current.add(child)
                isSuffix(child) || child is JsonnetObjectLiteral -> current.add(child)
                else -> {
                    operands.add(Operand(current))
                    current = mutableListOf(child)
                }
            }
            child = child.nextSibling
        }
        if (current.isNotEmpty()) operands.add(Operand(current))
        return operands to operators
    }

    /** One resolution's cycle/depth guard — `local a = a.b`, `self` referring back into itself, mutually importing files. */
    private class Session {
        private var depth = 0
        private val active = HashSet<PsiElement>()

        private inline fun guarded(key: PsiElement, body: () -> JsonnetStaticValue): JsonnetStaticValue {
            ProgressManager.checkCanceled()
            if (depth >= MAX_DEPTH || !active.add(key)) return JsonnetStaticValue.EMPTY
            depth++
            try {
                return body()
            } finally {
                depth--
                active.remove(key)
            }
        }

        fun evalExpr(expr: JsonnetExpr): JsonnetStaticValue = guarded(expr) {
            val (operands, operators) = split(expr)
            // Only `+` composes objects; any other operator produces a non-object.
            if (operators.any { it != JsonnetTypes.PLUS }) return@guarded JsonnetStaticValue.EMPTY
            operands.fold(JsonnetStaticValue.EMPTY) { acc, operand -> acc + evalOperand(operand, operand.elements.size) }
        }

        fun evalOperand(operand: Operand, upTo: Int): JsonnetStaticValue {
            var value = evalAtom(operand.elements[0])
            for (i in 1 until upTo) {
                if (value.isEmpty) break
                value = applySuffix(value, operand.elements[i])
            }
            return value
        }

        private fun evalAtom(atom: PsiElement): JsonnetStaticValue {
            val type = atom.node.elementType
            return when {
                atom is JsonnetObjectLiteral -> JsonnetStaticValue(listOf(atom))
                type == JsonnetTypes.SELF_KW -> selfOf(atom)
                type == JsonnetTypes.SUPER_KW -> enclosingObject(atom)?.let { superOf(it) } ?: JsonnetStaticValue.EMPTY
                type == JsonnetTypes.DOLLAR ->
                    JsonnetResolver.outermostObjectLiteral(atom)?.let { JsonnetStaticValue(listOf(it)) } ?: JsonnetStaticValue.EMPTY
                atom is JsonnetNameRef -> evalNameRef(atom)
                atom is JsonnetParenExpr -> atom.expr?.let { evalExpr(it) } ?: JsonnetStaticValue.EMPTY
                atom is JsonnetLocalExpr -> atom.expr?.let { evalExpr(it) } ?: JsonnetStaticValue.EMPTY
                atom is JsonnetAssertExpr -> atom.exprList.lastOrNull()?.let { evalExpr(it) } ?: JsonnetStaticValue.EMPTY
                atom is JsonnetIfExpr ->
                    atom.exprList.drop(1).fold(JsonnetStaticValue.EMPTY) { acc, branch -> acc + evalExpr(branch) }
                atom is JsonnetFunctionExpr -> JsonnetStaticValue(callables = listOf(atom))
                atom is JsonnetImportExpr -> evalImport(atom)
                else -> JsonnetStaticValue.EMPTY
            }
        }

        private fun evalNameRef(nameRef: JsonnetNameRef): JsonnetStaticValue {
            val name = nameRef.nameIdentifier?.text ?: return JsonnetStaticValue.EMPTY
            val bind = JsonnetResolver.resolveLocalName(nameRef, name) as? JsonnetBind ?: return JsonnetStaticValue.EMPTY
            if (bind.paramList != null) return JsonnetStaticValue(callables = listOf(bind))
            return bind.expr?.let { evalExpr(it) } ?: JsonnetStaticValue.EMPTY
        }

        private fun evalImport(importExpr: JsonnetImportExpr): JsonnetStaticValue {
            // `importstr`/`importbin` yield a string, not an object.
            if (importExpr.node.firstChildNode?.elementType != JsonnetTypes.IMPORT_KW) return JsonnetStaticValue.EMPTY
            val literal = importExpr.node.findChildByType(JsonnetTypes.STRING)?.text ?: return JsonnetStaticValue.EMPTY
            val fromFile = importExpr.containingFile?.originalFile?.virtualFile ?: return JsonnetStaticValue.EMPTY
            val target = TankaJpath.resolveImport(fromFile, unquote(literal))?.takeIf { !it.isDirectory }
                ?: return JsonnetStaticValue.EMPTY
            val psiFile = PsiManager.getInstance(importExpr.project).findFile(target) as? JsonnetFile
                ?: return JsonnetStaticValue.EMPTY
            return PsiTreeUtil.getChildOfType(psiFile, JsonnetExpr::class.java)?.let { evalExpr(it) }
                ?: JsonnetStaticValue.EMPTY
        }

        private fun applySuffix(value: JsonnetStaticValue, suffix: PsiElement): JsonnetStaticValue = when (suffix) {
            is JsonnetDotSuffix -> suffix.nameIdentifier?.text?.let { fieldValues(value, it) } ?: JsonnetStaticValue.EMPTY
            is JsonnetIndexSuffix -> stringIndex(suffix)?.let { fieldValues(value, it) } ?: JsonnetStaticValue.EMPTY
            is JsonnetCallSuffix -> call(value)
            is JsonnetObjectLiteral -> value.copyObjectsOnly() + JsonnetStaticValue(listOf(suffix))
            else -> JsonnetStaticValue.EMPTY
        }

        private fun JsonnetStaticValue.copyObjectsOnly() = JsonnetStaticValue(objects)

        /** `x['name']` with a plain string literal index; anything else (slices, computed) is unknown. */
        private fun stringIndex(suffix: JsonnetIndexSuffix): String? {
            if (suffix.node.findChildByType(JsonnetTypes.COLON) != null) return null
            val expr = suffix.exprList.singleOrNull() ?: return null
            val only = expr.node.getChildren(null).singleOrNull { it.psi !is PsiWhiteSpace } ?: return null
            return if (only.elementType == JsonnetTypes.STRING) unquote(only.text) else null
        }

        private fun fieldValues(value: JsonnetStaticValue, name: String): JsonnetStaticValue =
            value.fieldsNamed(name).fold(JsonnetStaticValue.EMPTY) { acc, field -> acc + fieldValue(field) }

        private fun fieldValue(field: JsonnetField): JsonnetStaticValue = guarded(field) {
            val own = if (field.paramList != null) {
                JsonnetStaticValue(callables = listOf(field))
            } else {
                field.expr?.let { evalExpr(it) } ?: JsonnetStaticValue.EMPTY
            }
            // `name+: {...}` extends the same field of the object being overridden.
            val inherited = if (field.node.findChildByType(PLUS_FIELD_OPS) != null) {
                val name = JsonnetResolver.fieldNameText(field)
                val owner = PsiTreeUtil.getParentOfType(field, JsonnetObjectLiteral::class.java)
                if (name != null && owner != null) fieldValues(superOf(owner), name) else JsonnetStaticValue.EMPTY
            } else {
                JsonnetStaticValue.EMPTY
            }
            inherited + own
        }

        private fun call(value: JsonnetStaticValue): JsonnetStaticValue =
            value.callables.fold(JsonnetStaticValue.EMPTY) { acc, callable ->
                val body: JsonnetExpr? = when (callable) {
                    is JsonnetBind -> callable.expr
                    is JsonnetField -> callable.expr
                    is JsonnetFunctionExpr -> callable.expr
                    else -> null
                }
                acc + (body?.let { evalExpr(it) } ?: JsonnetStaticValue.EMPTY)
            }

        private fun enclosingObject(from: PsiElement) = JsonnetResolver.enclosingObjectLiteral(from)

        /** `self`: the enclosing object plus whatever it extends (`base { ... }`, `base + { ... }`). */
        private fun selfOf(from: PsiElement): JsonnetStaticValue {
            val obj = enclosingObject(from) ?: return JsonnetStaticValue.EMPTY
            return superOf(obj) + JsonnetStaticValue(listOf(obj))
        }

        /** What [obj] is layered on top of: the prefix of its `x { ... }` chain, or the `+` operands before it. */
        fun superOf(obj: JsonnetObjectLiteral): JsonnetStaticValue = guarded(obj) {
            val expr = obj.parent as? JsonnetExpr ?: return@guarded JsonnetStaticValue.EMPTY
            val (operands, operators) = split(expr)
            for ((operandIndex, operand) in operands.withIndex()) {
                val elementIndex = operand.elements.indexOf(obj)
                if (elementIndex < 0) continue
                if (elementIndex > 0) return@guarded evalOperand(operand, upTo = elementIndex)
                if (operators.take(operandIndex).any { it != JsonnetTypes.PLUS }) return@guarded JsonnetStaticValue.EMPTY
                return@guarded operands.take(operandIndex)
                    .fold(JsonnetStaticValue.EMPTY) { acc, previous -> acc + evalOperand(previous, previous.elements.size) }
            }
            JsonnetStaticValue.EMPTY
        }
    }

    private fun unquote(text: String): String =
        if (text.length >= 2 && (text.startsWith("\"") || text.startsWith("'"))) text.substring(1, text.length - 1) else text
}
