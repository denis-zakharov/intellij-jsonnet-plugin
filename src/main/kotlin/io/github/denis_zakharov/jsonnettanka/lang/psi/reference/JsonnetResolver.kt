package io.github.denis_zakharov.jsonnettanka.lang.psi.reference

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetArrayComprehension
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetComputedFieldName
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetFunctionExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetLocalExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetObjectComprehension
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetObjectLiteral
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetParam
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetParamList
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Static-scope resolution for plain identifier references (`local`/function
 * params/comprehension loop variables). Deliberately simple: walks the PSI
 * parent chain and checks each lexical scope in turn — good enough for
 * go-to-definition, not a full binder (doesn't model shadowing edge cases
 * beyond "nearest enclosing scope wins", which matches Jsonnet's own rules).
 */
object JsonnetResolver {

    fun resolveLocalName(from: PsiElement, name: String): PsiElement? {
        var context: PsiElement? = from
        var inKey = false
        while (context != null) {
            val parent = context.parent ?: return null
            if (context is JsonnetComputedFieldName) inKey = true
            val visible = declarationsIntroducedBy(parent, inKey)
            visible.firstOrNull { declaredName(it) == name }?.let { return it }
            if (inKey && endsKeyScope(parent)) inKey = false
            context = parent
        }
        return null
    }

    /** The object (literal or comprehension) whose field name we are inside: past it, keys no longer matter. */
    private fun endsKeyScope(scope: PsiElement) = scope is JsonnetObjectLiteral || scope is JsonnetObjectComprehension

    /**
     * Every local/param/loop-variable declaration visible from [from], innermost scope first and
     * with shadowed names dropped — the candidate list for name completion.
     */
    fun visibleDeclarations(from: PsiElement): List<PsiElement> {
        val seen = HashSet<String>()
        val result = mutableListOf<PsiElement>()
        var context: PsiElement? = from
        var inKey = false
        while (context != null) {
            val parent = context.parent ?: break
            if (context is JsonnetComputedFieldName) inKey = true
            for (declaration in declarationsIntroducedBy(parent, inKey)) {
                val name = declaredName(declaration) ?: continue
                if (seen.add(name)) result.add(declaration)
            }
            if (inKey && endsKeyScope(parent)) inKey = false
            context = parent
        }
        return result
    }

    fun declaredName(declaration: PsiElement): String? = when (declaration) {
        is JsonnetBind -> declaration.nameIdentifier?.text
        is JsonnetParam -> declaration.nameIdentifier?.text
        is JsonnetForSpec -> declaration.nameIdentifier?.text
        else -> null
    }

    /**
     * The names [scope] brings into scope for its children (whether or not a given child is inside it).
     *
     * [inKey]: the reference sits in a computed field name (`[expr]: ...`). Jsonnet evaluates those outside the object,
     * so the object's `local`s and the field's own parameters are not visible there; comprehension loop variables are.
     */
    private fun declarationsIntroducedBy(scope: PsiElement, inKey: Boolean = false): List<PsiElement> = when (scope) {
        is JsonnetLocalExpr -> scope.bindList
        // `local f(x) = ...`-style function-sugar: `x` must resolve inside the body.
        is JsonnetBind -> scope.paramList?.paramList.orEmpty()
        is JsonnetParamList -> scope.paramList
        is JsonnetFunctionExpr -> scope.paramList?.paramList.orEmpty()
        is JsonnetField -> if (inKey) emptyList() else scope.paramList?.paramList.orEmpty()
        is JsonnetArrayComprehension -> scope.forSpecList
        is JsonnetObjectComprehension ->
            scope.forSpecList + if (inKey) emptyList() else scope.objectLocalList.mapNotNull { it.bind }
        is JsonnetObjectLiteral ->
            if (inKey) emptyList() else scope.objectMemberList?.objectLocalList.orEmpty().mapNotNull { it.bind }
        else -> emptyList()
    }

    /** Direct field declarations (not comprehensions, not inherited via `+`) of [obj]. */
    fun directFields(obj: JsonnetObjectLiteral): List<JsonnetField> =
        obj.objectMemberList?.fieldList.orEmpty()

    fun fieldNameText(field: JsonnetField): String? {
        val first = field.node.firstChildNode ?: return null
        return when (first.elementType) {
            JsonnetTypes.IDENTIFIER -> first.text
            JsonnetTypes.STRING -> unquote(first.text)
            else -> null
        }
    }

    /** `::`/`+::`/`:::` — a Jsonnet-idiomatic "private" field that never contributes to the object's rendered JSON. */
    fun isHiddenField(field: JsonnetField): Boolean = field.node.findChildByType(HIDDEN_FIELD_OPS) != null

    private val HIDDEN_FIELD_OPS = com.intellij.psi.tree.TokenSet.create(
        JsonnetTypes.COLONCOLON, JsonnetTypes.COLONCOLONCOLON,
        JsonnetTypes.PLUSCOLONCOLON, JsonnetTypes.PLUSCOLONCOLONCOLON,
    )

    private fun unquote(text: String): String {
        if (text.length >= 2 && (text.startsWith("\"") || text.startsWith("'"))) {
            return text.substring(1, text.length - 1)
        }
        return text
    }

    /**
     * Nearest enclosing object literal, for resolving `self`. A computed field name is evaluated outside the object it
     * names a field of, so from inside one that object is skipped (`{ a: 1, [self.a]: 2 }` is an error in Jsonnet).
     */
    fun enclosingObjectLiteral(from: PsiElement): JsonnetObjectLiteral? = objectsAround(from).firstOrNull()

    /** Outermost object literal enclosing [from], for `$` — Jsonnet defines `$` that way, not as "the file's root object". */
    fun outermostObjectLiteral(from: PsiElement): JsonnetObjectLiteral? = objectsAround(from).lastOrNull()

    /** The object literals whose body [from] is in, innermost first (see [enclosingObjectLiteral] for the key rule). */
    private fun objectsAround(from: PsiElement): List<JsonnetObjectLiteral> {
        val result = mutableListOf<JsonnetObjectLiteral>()
        var inKey = false
        var context: PsiElement = from
        while (true) {
            val parent = context.parent ?: break
            if (context is JsonnetComputedFieldName) inKey = true
            if (parent is JsonnetObjectLiteral) {
                if (inKey) inKey = false else result.add(parent)
            }
            context = parent
        }
        return result
    }
}
