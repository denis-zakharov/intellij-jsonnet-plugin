package io.github.denis_zakharov.jsonnettanka.stdlib

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

/** Suggests `std.*` members after `std.`, functions with their parameters — see [StdLibRegistry] for where the list comes from. */
class StdLibCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().withLanguage(JsonnetLanguage).withParent(JsonnetDotSuffix::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val dotSuffix = parameters.position.parent as? JsonnetDotSuffix ?: return
                    if (!StdLibRegistry.isStdMemberAccess(dotSuffix)) return
                    for (name in StdLibRegistry.memberNames) {
                        result.addElement(lookupElement(name))
                    }
                }
            },
        )
    }

    internal companion object {
        /** Functions show their parameters and get `(` `)` on insert; `pi`/`thisFile`-style values stay plain. */
        fun lookupElement(name: String): LookupElement {
            val builder = LookupElementBuilder.create(name).withTypeText("std", true)
            val parameters = StdLibRegistry.parameters(name)
                ?: return builder.withIcon(AllIcons.Nodes.Field)
            return builder
                .withIcon(AllIcons.Nodes.Function)
                .withTailText("(${parameters.joinToString(", ") { it.display }})", true)
                .withInsertHandler(ParenthesesInsertHandler.getInstance(parameters.isNotEmpty()))
        }
    }
}
