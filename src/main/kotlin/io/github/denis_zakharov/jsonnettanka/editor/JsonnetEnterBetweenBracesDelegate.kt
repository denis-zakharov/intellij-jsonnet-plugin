package io.github.denis_zakharov.jsonnettanka.editor

import com.intellij.codeInsight.editorActions.enter.EnterBetweenBracesDelegate

/**
 * Enter between an empty pair puts the closer on its own line and leaves the caret indented between them. The platform's
 * default only knows `{}`; Jsonnet arrays and call/parameter lists are written the same way.
 */
class JsonnetEnterBetweenBracesDelegate : EnterBetweenBracesDelegate() {
    override fun isBracePair(lBrace: Char, rBrace: Char): Boolean =
        (lBrace == '{' && rBrace == '}') || (lBrace == '[' && rBrace == ']') || (lBrace == '(' && rBrace == ')')
}
