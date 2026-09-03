package com.dz.intellijjsonnet.tanka

import com.intellij.openapi.vfs.VirtualFile

/**
 * Mirrors `grafana/tanka`'s `pkg/jpath.Resolve` (independently reimplemented
 * from its documented behavior — see plan §5, not ported from the AGPL-3.0
 * `jsonnet-language-server`): walk up from a file to find `root` (nearest
 * ancestor with `jsonnetfile.json`) and `base` (nearest ancestor with
 * `main.jsonnet`); the effective import search path is
 * `[base, root/vendor, root/lib]`.
 */
object TankaJpath {

    const val JSONNETFILE = "jsonnetfile.json"
    const val MAIN_JSONNET = "main.jsonnet"

    fun findRoot(startDir: VirtualFile): VirtualFile? = ancestorContaining(startDir, JSONNETFILE)

    fun findBase(startDir: VirtualFile): VirtualFile? = ancestorContaining(startDir, MAIN_JSONNET)

    /** Additional search roots for imports that don't resolve relative to the importing file. */
    fun searchPath(fromFile: VirtualFile): List<VirtualFile> {
        val startDir = if (fromFile.isDirectory) fromFile else fromFile.parent ?: return emptyList()
        val base = findBase(startDir)
        val root = findRoot(startDir)
        return listOfNotNull(base, root?.findChild("vendor"), root?.findChild("lib"))
    }

    private fun ancestorContaining(startDir: VirtualFile, childName: String): VirtualFile? {
        var dir: VirtualFile? = startDir
        while (dir != null) {
            if (dir.findChild(childName)?.isDirectory == false) return dir
            dir = dir.parent
        }
        return null
    }
}
