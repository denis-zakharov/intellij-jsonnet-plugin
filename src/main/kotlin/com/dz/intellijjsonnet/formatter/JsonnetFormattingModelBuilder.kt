package com.dz.intellijjsonnet.formatter

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider

class JsonnetFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val settings = formattingContext.codeStyleSettings
        val block = JsonnetBlock(
            formattingContext.node,
            null,
            null,
            jsonnetSpacingBuilder(settings),
        )
        return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.containingFile, block, settings)
    }
}
