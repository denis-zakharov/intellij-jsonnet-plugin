package io.github.denis_zakharov.jsonnettanka.formatter

/**
 * Files `tk fmt` never touches: hidden files and directories, and anything under a `vendor` directory. Vendored libraries are somebody else's
 * source and hidden files are tooling state, so Reformat Code leaves both alone (unless the user turns
 * [JsonnetCodeStyleSettings.SKIP_VENDOR_AND_DOTFILES] off).
 *
 * Standalone (strings only) so it is directly unit-testable.
 */
internal object JsonnetFormatExclusions {
    /**
     * Whether [path] is excluded. Components are judged relative to [basePath] (the project root) when the file is inside
     * it, so a project that happens to live under `~/.work/` isn't excluded wholesale; outside the project only the file's
     * own name and a `vendor` directory count.
     */
    fun isExcluded(path: String, basePath: String?): Boolean {
        val normalized = path.replace('\\', '/')
        val base = basePath?.replace('\\', '/')?.trimEnd('/')
        val relative = if (!base.isNullOrEmpty() && normalized.startsWith("$base/")) normalized.substring(base.length + 1) else null
        val components = (relative ?: normalized).split('/').filter { it.isNotEmpty() }
        if (components.isEmpty()) return false
        if (components.last().startsWith(".")) return true
        val dirs = components.dropLast(1)
        return dirs.any { it == "vendor" } || (relative != null && dirs.any { it.startsWith(".") })
    }
}
