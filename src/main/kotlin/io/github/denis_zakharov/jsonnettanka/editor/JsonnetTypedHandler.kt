package io.github.denis_zakharov.jsonnettanka.editor

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes

/**
 * A closer (`}`, `]`, `)`) typed as the first thing on its line goes back to the indent of its opener. The platform
 * already does this for `}` and `)` (and only those two, hard-coded) through the highlighter's brace matcher; doing it
 * here from the PSI covers `]` and makes the other two independent of that machinery. Re-indenting twice is harmless.
 */
class JsonnetTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (CLOSERS.indexOf(c) < 0 || !file.language.isKindOf(JsonnetLanguage)) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset - 1
        if (offset < 0 || document.charsSequence[offset] != c) return Result.CONTINUE
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        if (!document.charsSequence.subSequence(lineStart, offset).isBlank()) return Result.CONTINUE

        PsiDocumentManager.getInstance(project).commitDocument(document)
        // Not a closer inside a string or comment.
        if (file.findElementAt(offset)?.node?.elementType !in CLOSER_TOKENS) return Result.CONTINUE
        CodeStyleManager.getInstance(project).adjustLineIndent(file, offset)
        return Result.CONTINUE
    }

    private companion object {
        const val CLOSERS = "}])"
        val CLOSER_TOKENS = setOf(JsonnetTypes.RBRACE, JsonnetTypes.RBRACK, JsonnetTypes.RPAREN)
    }
}
