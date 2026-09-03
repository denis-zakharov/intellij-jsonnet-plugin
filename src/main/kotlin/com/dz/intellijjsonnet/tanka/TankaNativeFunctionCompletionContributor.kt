package com.dz.intellijjsonnet.tanka

import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

/** Suggests known Tanka native-function names inside `std.native('...')`. */
class TankaNativeFunctionCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement(JsonnetTypes.STRING).withLanguage(JsonnetLanguage),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val element = parameters.position
                    if (!TankaNativeFunctions.isNativeNameArgument(element)) return
                    val prefix = element.text.trimStart('\'', '"').substringBefore(CompletionUtilDummyMarker)
                    val resultWithPrefix = result.withPrefixMatcher(prefix)
                    for (entry in TankaNativeFunctions.entries) {
                        resultWithPrefix.addElement(
                            LookupElementBuilder.create(entry.name)
                                .withIcon(AllIcons.Nodes.Function)
                                .withTypeText("tanka native", true)
                                .withTailText(" — ${entry.description}", true),
                        )
                    }
                }
            },
        )
    }

    private companion object {
        // IntelliJ's completion machinery inserts this marker at the caret before
        // reparsing; strip everything from it onward to get the real typed prefix.
        const val CompletionUtilDummyMarker = "IntellijIdeaRulezzz"
    }
}
