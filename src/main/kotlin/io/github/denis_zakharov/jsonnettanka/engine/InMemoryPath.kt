package io.github.denis_zakharov.jsonnettanka.engine

import io.github.denis_zakharov.jsonnettanka.shaded.sjsonnet.Path
import io.github.denis_zakharov.jsonnettanka.shaded.scala.collection.mutable.HashMap as ScalaHashMap

/**
 * Minimal [Path] backed by a string we already hold, with no filesystem access.
 * Phase 0 spike for the VFS/PSI-backed Path+Importer described in the plan (§4.4) —
 * grown into a real VirtualFile-backed implementation in later phases.
 */
class InMemoryPath(
    private val displayName: String,
    private val content: String,
) : Path {

    override fun relativeToString(base: Path): String = displayName

    override fun parent(): Path = this

    override fun segmentCount(): Int = 1

    override fun last(): String = displayName

    override fun `$div`(segment: String): Path = InMemoryPath("$displayName/$segment", "")

    override fun renderOffsetStr(offset: Int, cache: ScalaHashMap<Path, IntArray>): String {
        var line = 1
        var col = 1
        for (i in 0 until offset.coerceAtMost(content.length)) {
            if (content[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return "$displayName:$line:$col"
    }

    override fun toString(): String = displayName
}
