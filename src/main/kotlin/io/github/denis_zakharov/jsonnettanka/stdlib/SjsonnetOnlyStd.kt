package io.github.denis_zakharov.jsonnettanka.stdlib

/**
 * `std` members that sjsonnet (the preview engine) defines but go-jsonnet (what `tk` runs) doesn't. Code using
 * them evaluates fine in the preview and then fails under `tk` with `Field does not exist`, so
 * `JsonnetSjsonnetOnlyStdInspection` flags them. See `docs/sjsonnet-gaps.md`.
 *
 * The list is measured, not guessed: `scripts/sjsonnet-conformance.py std` diffs both `std` key sets and reports
 * any drift from it (a sjsonnet bump can add members; a go-jsonnet bump can adopt these), and
 * `SjsonnetOnlyStdTest` pins that every entry is still a real sjsonnet member.
 */
object SjsonnetOnlyStd {

    /** [portable] is what to write instead, or how to work around the gap — Tanka's natives are the only shared ground. */
    data class Entry(val name: String, val portable: String)

    val entries: List<Entry> = listOf(
        Entry(
            "regexFullMatch",
            "std.native('regexMatch')(pattern, str) is portable but tests for a partial match and returns only a boolean; " +
                "anchor the pattern with ^(?:…)\$ for a full match. Captures aren't available",
        ),
        Entry(
            "regexPartialMatch",
            "std.native('regexMatch')(pattern, str) is portable but returns only a boolean; captures aren't available",
        ),
        Entry(
            "regexGlobalReplace",
            "std.native('regexSubst')(pattern, str, to) is portable (note the argument order)",
        ),
        Entry(
            "regexReplace",
            "no portable equivalent replaces only the first match; std.native('regexSubst') replaces all of them",
        ),
        Entry(
            "regexQuoteMeta",
            "std.native('escapeStringRegex')(str) is portable",
        ),
    )

    val names: Set<String> = entries.map { it.name }.toSet()

    fun find(name: String): Entry? = entries.firstOrNull { it.name == name }
}
