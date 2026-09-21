package io.github.denis_zakharov.jsonnettanka.editor

import io.github.denis_zakharov.jsonnettanka.lang.lexer.JsonnetLexerAdapter
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetForSpec
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetParam
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.TokenSet

/**
 * Registers the word index over Jsonnet files (which text-based reference search — Find Usages, rename's
 * "other usages" pass, [io.github.denis_zakharov.jsonnettanka.inspection.JsonnetFieldUsageSearch] — relies on) and
 * enables Find Usages on declarations.
 */
class JsonnetFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        JsonnetLexerAdapter(),
        TokenSet.create(JsonnetTypes.IDENTIFIER),
        TokenSet.create(JsonnetTypes.COMMENT),
        TokenSet.create(JsonnetTypes.STRING),
    )

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean = psiElement is PsiNamedElement

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element) {
        is JsonnetBind -> if (element.paramList != null) "function" else "local"
        is JsonnetParam -> "parameter"
        is JsonnetForSpec -> "loop variable"
        is JsonnetField -> "field"
        else -> ""
    }

    override fun getDescriptiveName(element: PsiElement): String = (element as? PsiNamedElement)?.name.orEmpty()

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}
