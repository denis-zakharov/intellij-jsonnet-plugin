package com.dz.intellijjsonnet.tanka

import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.dz.intellijjsonnet.lang.psi.JsonnetDotSuffix
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

/** Suggests `env`/`spec`/`metadata`/spec-fields after `<tk-import-var>.env(.spec)?.` — see [TankaTkModule]. */
class TankaTkModuleCompletionContributor : CompletionContributor() {
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
                    val chain = TankaTkModule.tkAccessChainBefore(dotSuffix) ?: return
                    val names = when (chain) {
                        emptyList<String>() -> listOf("env")
                        listOf("env") -> TankaTkModule.envFields
                        listOf("env", "spec") -> TankaTkModule.specFields
                        else -> return
                    }
                    for (name in names) {
                        result.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Field).withTypeText("tk", true))
                    }
                }
            },
        )
    }
}
