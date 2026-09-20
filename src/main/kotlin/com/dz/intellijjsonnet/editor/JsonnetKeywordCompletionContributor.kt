package com.dz.intellijjsonnet.editor

import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.dz.intellijjsonnet.lang.psi.JsonnetNameRef
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

/**
 * Where an expression can start (the caret is in a bare identifier): the keywords that begin one, plus
 * `std`. Local names come from [com.dz.intellijjsonnet.lang.psi.reference.JsonnetLocalReference.getVariants].
 */
class JsonnetKeywordCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().withLanguage(JsonnetLanguage).withParent(JsonnetNameRef::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    result.addElement(LookupElementBuilder.create("std").withIcon(AllIcons.Nodes.Static).withTypeText("standard library", true))
                    for (keyword in EXPRESSION_KEYWORDS) {
                        result.addElement(LookupElementBuilder.create(keyword).bold())
                    }
                    for (keyword in IMPORT_KEYWORDS) {
                        result.addElement(LookupElementBuilder.create(keyword).bold().withInsertHandler(QUOTED_PATH_HANDLER))
                    }
                }
            },
        )
    }

    private companion object {
        val EXPRESSION_KEYWORDS = listOf(
            "self", "super", "local", "if", "function", "error", "assert", "true", "false", "null",
        )
        val IMPORT_KEYWORDS = listOf("import", "importstr", "importbin")

        /** `import` → `import '|'`, and straight into path completion. */
        val QUOTED_PATH_HANDLER = InsertHandler<LookupElement> { context, _ ->
            val editor = context.editor
            editor.document.insertString(context.tailOffset, " ''")
            editor.caretModel.moveToOffset(context.tailOffset - 1)
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(editor)
        }
    }
}
