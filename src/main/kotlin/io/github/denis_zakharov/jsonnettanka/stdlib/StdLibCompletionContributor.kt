package io.github.denis_zakharov.jsonnettanka.stdlib

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

/** Suggests `std.*` member names after `std.` — see [StdLibRegistry] for where the list comes from. */
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
                        result.addElement(
                            LookupElementBuilder.create(name)
                                .withIcon(AllIcons.Nodes.Function)
                                .withTypeText("std", true),
                        )
                    }
                }
            },
        )
    }
}
