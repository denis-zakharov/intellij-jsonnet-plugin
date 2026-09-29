package io.github.denis_zakharov.jsonnettanka.engine

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * The one place the evaluator reads file text (root file and imports alike): the live editor
 * buffer when the file has a loaded [com.intellij.openapi.editor.Document] — so unsaved edits are
 * what Preview evaluates — and the VFS content otherwise. `getCachedDocument` (not `getDocument`)
 * on purpose: it never loads a document just to read it, and a file with no loaded document can't
 * have unsaved changes anyway.
 */
object VirtualFileText {

    fun read(file: VirtualFile): String = ApplicationManager.getApplication().runReadAction<String> {
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)
    }
}
