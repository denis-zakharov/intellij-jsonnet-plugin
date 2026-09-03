package com.dz.intellijjsonnet.tanka

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/** Project-wide discovery of `environments/<name>/main.jsonnet` directories — see [TankaJpath]'s directory conventions. */
object TankaEnvironments {

    /** Each environment's directory (the one directly containing `main.jsonnet`), keyed by its name. */
    fun findAll(project: Project): Map<String, VirtualFile> =
        FilenameIndex.getVirtualFilesByName(TankaJpath.MAIN_JSONNET, GlobalSearchScope.projectScope(project))
            .mapNotNull { it.parent }
            .filter { it.parent?.name == "environments" }
            .associateBy { it.name }

    /** The nearest ancestor `environments/<name>` directory containing [file], if any. */
    fun environmentOf(file: VirtualFile): VirtualFile? {
        var dir: VirtualFile? = if (file.isDirectory) file else file.parent
        while (dir != null) {
            if (dir.parent?.name == "environments") return dir
            dir = dir.parent
        }
        return null
    }
}
