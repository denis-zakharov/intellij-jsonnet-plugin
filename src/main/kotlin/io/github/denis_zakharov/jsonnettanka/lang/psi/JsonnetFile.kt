package io.github.denis_zakharov.jsonnettanka.lang.psi

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetFileType
import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class JsonnetFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, JsonnetLanguage) {
    override fun getFileType(): FileType = JsonnetFileType
    override fun toString(): String = "Jsonnet File"
}
