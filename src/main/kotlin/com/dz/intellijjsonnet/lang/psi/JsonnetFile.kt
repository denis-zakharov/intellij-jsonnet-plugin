package com.dz.intellijjsonnet.lang.psi

import com.dz.intellijjsonnet.lang.JsonnetFileType
import com.dz.intellijjsonnet.lang.JsonnetLanguage
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class JsonnetFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, JsonnetLanguage) {
    override fun getFileType(): FileType = JsonnetFileType
    override fun toString(): String = "Jsonnet File"
}
