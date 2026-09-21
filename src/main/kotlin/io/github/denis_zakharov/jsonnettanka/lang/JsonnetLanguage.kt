package io.github.denis_zakharov.jsonnettanka.lang

import com.intellij.lang.Language

object JsonnetLanguage : Language("Jsonnet") {
    private fun readResolve(): Any = JsonnetLanguage
}
