package io.github.denis_zakharov.jsonnettanka.fmt

/**
 * The go-jsonnet release `fmt/` is a port of, in one place. `scripts/jsonnetfmt-conformance.py` reads these two
 * constants (keep the `const val` lines as they are) to check the `jsonnetfmt` binary it compares against; the `Ported
 * to Kotlin from go-jsonnet ...` headers, AGENTS.md and THIRD_PARTY_NOTICES.md quote the same values.
 */
internal object PortedFrom {
    const val GO_JSONNET_VERSION = "v0.22.0"
    const val GO_JSONNET_COMMIT = "567b61a"
}
