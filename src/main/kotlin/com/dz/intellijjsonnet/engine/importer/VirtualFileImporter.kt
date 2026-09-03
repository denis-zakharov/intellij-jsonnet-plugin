package com.dz.intellijjsonnet.engine.importer

import com.dz.intellijjsonnet.engine.VirtualFilePath
import com.dz.intellijjsonnet.shaded.scala.Option
import com.dz.intellijjsonnet.shaded.scala.`Option$`
import com.dz.intellijjsonnet.shaded.sjsonnet.Importer
import com.dz.intellijjsonnet.shaded.sjsonnet.Path
import com.dz.intellijjsonnet.shaded.sjsonnet.ResolvedFile
import com.dz.intellijjsonnet.shaded.sjsonnet.StaticResolvedFile
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * Resolves `import`/`importstr`/`importbin` against the IDE's VFS instead of
 * raw disk I/O — the custom Importer the plan (§4.4) calls for, so imports see
 * the same project tree the IDE does. Extra search roots (Tanka jpath,
 * jb vendor/) are a Phase 3 concern; this is plain relative-to-current-file
 * resolution only.
 */
class VirtualFileImporter : Importer() {

    override fun resolve(docBase: Path, importName: String): Option<Path> {
        val base = docBase as? VirtualFilePath ?: return none()
        val dir = if (base.file.isDirectory) base.file else base.file.parent ?: return none()
        val target = VfsUtilCore.findRelativeFile(importName, dir) ?: return none()
        return some(VirtualFilePath(target))
    }

    override fun read(path: Path, binaryData: Boolean): Option<ResolvedFile> {
        val vfp = path as? VirtualFilePath ?: return none()
        if (vfp.file.isDirectory) return none()
        val content = try {
            VfsUtilCore.loadText(vfp.file)
        } catch (e: Exception) {
            return none()
        }
        return some(StaticResolvedFile(content) as ResolvedFile)
    }

    private fun <T> some(value: T): Option<T> = `Option$`.`MODULE$`.apply(value)

    @Suppress("UNCHECKED_CAST")
    private fun <T> none(): Option<T> = `Option$`.`MODULE$`.apply<T>(null as T)
}
