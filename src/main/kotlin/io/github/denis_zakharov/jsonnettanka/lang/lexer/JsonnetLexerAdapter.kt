package io.github.denis_zakharov.jsonnettanka.lang.lexer

import com.intellij.lexer.FlexAdapter

class JsonnetLexerAdapter : FlexAdapter(JsonnetLexer(null))
