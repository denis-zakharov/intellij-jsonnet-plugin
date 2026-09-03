package com.dz.intellijjsonnet.lang

import com.intellij.lang.Language

object JsonnetLanguage : Language("Jsonnet") {
    private fun readResolve(): Any = JsonnetLanguage
}
