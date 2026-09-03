package com.dz.intellijjsonnet.engine

import com.dz.intellijjsonnet.shaded.sjsonnet.Path
import com.dz.intellijjsonnet.shaded.scala.collection.mutable.HashMap as ScalaHashMap
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * [Path] backed by a real [VirtualFile], so imports resolve against the IDE's
 * own view of the project tree. Reads go straight to disk via the VFS (not
 * through unsaved editor buffers) — a deliberate v1 simplification; honoring
 * unsaved buffers is a natural follow-up once this proves out.
 */
class VirtualFilePath(val file: VirtualFile) : Path {

    override fun relativeToString(base: Path): String {
        val baseDir = (base as? VirtualFilePath)?.file ?: return file.path
        return VfsUtilCore.getRelativePath(file, baseDir) ?: file.path
    }

    override fun parent(): Path = VirtualFilePath(file.parent ?: file)

    override fun segmentCount(): Int = file.path.count { it == '/' } + 1

    override fun last(): String = file.name

    override fun `$div`(segment: String): Path {
        val dir = if (file.isDirectory) file else file.parent ?: file
        val target = VfsUtilCore.findRelativeFile(segment, dir)
        return VirtualFilePath(target ?: file)
    }

    override fun renderOffsetStr(offset: Int, cache: ScalaHashMap<Path, IntArray>): String {
        val text = readTextOrEmpty()
        var line = 1
        var col = 1
        for (i in 0 until offset.coerceAtMost(text.length)) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return "${file.path}:$line:$col"
    }

    fun readTextOrEmpty(): String = try {
        VfsUtilCore.loadText(file)
    } catch (e: Exception) {
        ""
    }

    override fun equals(other: Any?): Boolean = other is VirtualFilePath && other.file == file
    override fun hashCode(): Int = file.hashCode()
    override fun toString(): String = file.path
}
