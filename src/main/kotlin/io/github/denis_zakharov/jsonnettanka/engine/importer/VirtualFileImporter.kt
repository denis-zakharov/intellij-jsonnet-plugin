package io.github.denis_zakharov.jsonnettanka.engine.importer

import io.github.denis_zakharov.jsonnettanka.engine.VirtualFilePath
import io.github.denis_zakharov.jsonnettanka.engine.VirtualFileText
import io.github.denis_zakharov.jsonnettanka.shaded.scala.Option
import io.github.denis_zakharov.jsonnettanka.shaded.scala.`Option$`
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Importer
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Path
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.ResolvedFile
import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.StaticResolvedFile
import io.github.denis_zakharov.jsonnettanka.tanka.TankaJpath

/**
 * Resolves `import`/`importstr`/`importbin` against the IDE's VFS instead of
 * raw disk I/O — the custom Importer the plan (§4.4) calls for, so imports see
 * the same project tree the IDE does. Tries relative-to-the-importing-file
 * first, same as vanilla Jsonnet always does, then falls back to Tanka's
 * jpath search roots (`[base, root/vendor, root/lib]`, see [TankaJpath]) —
 * `jb install`-managed dependencies live under `vendor/` and are otherwise
 * unreachable from a file that doesn't sit next to them.
 */
class VirtualFileImporter : Importer() {

    override fun resolve(docBase: Path, importName: String): Option<Path> {
        val base = docBase as? VirtualFilePath ?: return none()
        val target = TankaJpath.resolveImport(base.file, importName) ?: return none()
        return some(VirtualFilePath(target))
    }

    override fun read(path: Path, binaryData: Boolean): Option<ResolvedFile> {
        val vfp = path as? VirtualFilePath ?: return none()
        if (vfp.file.isDirectory) return none()
        val content = try {
            VirtualFileText.read(vfp.file)
        } catch (e: Exception) {
            return none()
        }
        return some(StaticResolvedFile(content) as ResolvedFile)
    }

    private fun <T> some(value: T): Option<T> = `Option$`.`MODULE$`.apply(value)

    @Suppress("UNCHECKED_CAST")
    private fun <T> none(): Option<T> = `Option$`.`MODULE$`.apply<T>(null as T)
}
