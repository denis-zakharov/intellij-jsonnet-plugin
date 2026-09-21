package io.github.denis_zakharov.jsonnettanka.stdlib

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The plan (§6) originally called for a CI task diffing `Std.scala`'s builtin
 * names against a hand-maintained completion registry, to catch drift on a
 * dependency bump. That check is moot by construction here: [StdLibRegistry]
 * reads `std`'s member names directly off the pinned interpreter's own
 * runtime object (`Val$Obj.visibleKeyNames()`), so there's no separate list
 * to drift. This is the guard against the *mechanism* itself silently
 * breaking instead — e.g. if a future `sjsonnet` bump renames or removes
 * that method, `memberNames` would quietly go empty rather than fail loudly.
 */
class StdLibRegistrySanityTest {
    @Test
    fun `stdlib registry is populated with well-known functions`() {
        val names = StdLibRegistry.memberNames
        assertTrue(names.size > 50, "expected a substantial std member list, got ${names.size}: $names")
        for (expected in listOf("type", "length", "objectFields", "manifestJson", "extVar")) {
            assertTrue(expected in names, "expected '$expected' in std member names: $names")
        }
    }
}
