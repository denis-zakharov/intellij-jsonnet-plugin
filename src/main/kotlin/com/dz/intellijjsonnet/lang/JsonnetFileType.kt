package com.dz.intellijjsonnet.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

sealed class JsonnetFileTypeBase(private val extension: String) : LanguageFileType(JsonnetLanguage) {
    override fun getName(): String = "Jsonnet"
    override fun getDescription(): String = "Jsonnet file"
    override fun getDefaultExtension(): String = extension
    override fun getIcon(): Icon = JsonnetIcons.FILE
}

object JsonnetFileType : JsonnetFileTypeBase("jsonnet")
object LibsonnetFileType : JsonnetFileTypeBase("libsonnet")
