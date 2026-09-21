package io.github.denis_zakharov.jsonnettanka.editor

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.text.CharArrayUtil
import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes

/**
 * Enter in the middle of a `//` or `#` comment continues it on the new line (`// first⏎second` -> `// first⏎// second`),
 * like the platform does for languages whose commenter is a `CodeDocumentationAwareCommenter`. That one can't be used
 * here: line and block comments are the same token, so it would put `//` inside `/* ... */`. Enter at the end of a
 * comment does nothing special, as in the platform.
 */
class JsonnetEnterInLineCommentHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (!file.language.isKindOf(JsonnetLanguage)) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val caret = caretOffset.get()
        if (caret < 1) return EnterHandlerDelegate.Result.Continue

        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        val comment = file.findElementAt(caret - 1)?.takeIf { it.node.elementType == JsonnetTypes.COMMENT }
            ?: return EnterHandlerDelegate.Result.Continue
        val start = comment.textRange.startOffset
        val text = document.immutableCharSequence
        val prefix = PREFIXES.firstOrNull { comment.text.startsWith(it) } ?: return EnterHandlerDelegate.Result.Continue
        if (caret < start + prefix.length) return EnterHandlerDelegate.Result.Continue

        // Nothing but blanks after the caret: a plain Enter.
        val textStart = CharArrayUtil.shiftForward(text, caret, " \t")
        if (textStart >= comment.textRange.endOffset) return EnterHandlerDelegate.Result.Continue

        val beforeComment = CharArrayUtil.shiftBackward(text, start - 1, " \t")
        val onlyComment = beforeComment < 0 || text[beforeComment] == '\n'
        var spacing = " "
        if (onlyComment) {
            // Keep the comment's own spacing after the prefix (`//  x` continues as `//  `).
            val afterPrefix = start + prefix.length
            val existing = text.subSequence(afterPrefix, CharArrayUtil.shiftForward(text, afterPrefix, " \t"))
            if (existing.isNotEmpty()) spacing = existing.toString()
            document.deleteString(caret, textStart)
        } else if (text[caret] == ' ') {
            spacing = ""
        }
        document.insertString(caret, prefix + spacing)
        caretAdvance.set(prefix.length + spacing.length)
        return EnterHandlerDelegate.Result.DefaultForceIndent
    }

    private companion object {
        val PREFIXES = listOf("//", "#")
    }
}
