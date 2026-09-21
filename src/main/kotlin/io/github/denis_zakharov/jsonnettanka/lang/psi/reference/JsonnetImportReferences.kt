package io.github.denis_zakharov.jsonnettanka.lang.psi.reference

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetImportExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import io.github.denis_zakharov.jsonnettanka.tanka.TankaJpath
import io.github.denis_zakharov.jsonnettanka.tanka.TankaTkModule
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet

/**
 * `import`/`importstr`/`importbin` path strings resolve as file references,
 * first relative to the importing file (default [FileReferenceSet] behavior),
 * then against Tanka's jpath search roots (see [TankaJpath]) so a `vendor/`
 * or `lib/` import navigates correctly even from a file that isn't a sibling
 * of the dependency.
 *
 * The references are hosted by the [JsonnetImportExpr] (see `JsonnetImportExprMixin`), not by the
 * `STRING` token: a bare leaf never asks `PsiReferenceContributor`s for references, so a contributor
 * registered on the string would silently never fire.
 */
object JsonnetImportReferences {

    fun create(importExpr: JsonnetImportExpr): Array<PsiReference> {
        val literal = importExpr.node.findChildByType(JsonnetTypes.STRING) ?: return PsiReference.EMPTY_ARRAY
        val text = literal.text
        if (text.length < 2 || (text[0] != '\'' && text[0] != '"')) return PsiReference.EMPTY_ARRAY
        // `import 'tk'` is Tanka's synthetic environment-metadata module, not a
        // real file — treating it as a normal (and permanently unresolved) file
        // reference would just be a persistent false-positive error.
        if (TankaTkModule.isTkImportPath(text)) return PsiReference.EMPTY_ARRAY
        val path = text.substring(1, text.length - 1)
        val startInElement = literal.psi.startOffsetInParent + 1
        val fileReferenceSet = object : FileReferenceSet(path, importExpr, startInElement, null, true) {
            override fun getDefaultContexts(): MutableCollection<PsiFileSystemItem> {
                val contexts = super.getDefaultContexts()
                val virtualFile = element.containingFile?.originalFile?.virtualFile ?: return contexts
                val manager = PsiManager.getInstance(element.project)
                val jpathDirs = TankaJpath.searchPath(virtualFile).mapNotNull { toPsiDirectory(it, manager) }
                if (jpathDirs.isEmpty()) return contexts
                return (contexts + jpathDirs).toMutableList()
            }
        }
        @Suppress("UNCHECKED_CAST")
        return fileReferenceSet.allReferences as Array<PsiReference>
    }

    private fun toPsiDirectory(file: VirtualFile, manager: PsiManager): PsiFileSystemItem? =
        if (file.isDirectory) manager.findDirectory(file) else null
}
