package com.dz.intellijjsonnet.lang.lexer

import com.intellij.lexer.FlexAdapter

class JsonnetLexerAdapter : FlexAdapter(JsonnetLexer(null))
