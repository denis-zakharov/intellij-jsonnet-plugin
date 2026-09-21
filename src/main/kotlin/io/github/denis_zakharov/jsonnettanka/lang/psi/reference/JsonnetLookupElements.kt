package io.github.denis_zakharov.jsonnettanka.lang.psi.reference

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetParam
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetParamList
import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement

/** Completion entries for user-declared names; the lookup object is the declaration itself (so navigation/doc work). */
object JsonnetLookupElements {

    private val IDENTIFIER = Regex("[_a-zA-Z][_a-zA-Z0-9]*")

    /** Names like `'foo-bar'` are legal field names but can't follow a `.`. */
    fun isPlainIdentifier(name: String) = IDENTIFIER.matches(name)

    fun forField(field: JsonnetField): LookupElement {
        val name = JsonnetResolver.fieldNameText(field).orEmpty()
        val params = field.paramList
        return LookupElementBuilder.create(field, name)
            .withIcon(if (params != null) AllIcons.Nodes.Method else AllIcons.Nodes.Field)
            .withTypeText(field.containingFile?.name, true)
            .withItemTextItalic(JsonnetResolver.isHiddenField(field))
            .let { withParams(it, params) }
    }

    fun forDeclaration(declaration: PsiElement): LookupElement? {
        val name = JsonnetResolver.declaredName(declaration) ?: return null
        val builder = LookupElementBuilder.create(declaration, name)
        return when (declaration) {
            is JsonnetBind -> withParams(
                builder.withIcon(if (declaration.paramList != null) AllIcons.Nodes.Function else AllIcons.Nodes.Variable),
                declaration.paramList,
            ).withTypeText("local", true)
            is JsonnetParam -> builder.withIcon(AllIcons.Nodes.Parameter).withTypeText("parameter", true)
            is JsonnetForSpec -> builder.withIcon(AllIcons.Nodes.Variable).withTypeText("loop variable", true)
            else -> builder
        }
    }

    private fun withParams(builder: LookupElementBuilder, params: JsonnetParamList?): LookupElementBuilder {
        if (params == null) return builder
        val names = params.paramList.mapNotNull { it.nameIdentifier?.text }
        return builder
            .withTailText("(${names.joinToString(", ")})", true)
            .withInsertHandler(ParenthesesInsertHandler.getInstance(names.isNotEmpty()))
    }
}
