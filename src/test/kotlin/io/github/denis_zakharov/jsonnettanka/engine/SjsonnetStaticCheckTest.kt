package io.github.denis_zakharov.jsonnettanka.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SjsonnetStaticCheckTest {

    @Test
    fun `no unresolved variable in a fully-resolved file`() {
        val result = SjsonnetStaticCheck.firstUnresolvedVariable(
            "test.jsonnet",
            "local x = 1; { a: x, b: x + 1 }",
        )
        assertNull(result)
    }

    @Test
    fun `bare unresolved identifier is reported at its own offset`() {
        val source = "local x = 1; x + totallyUndefined"
        val result = SjsonnetStaticCheck.firstUnresolvedVariable("test.jsonnet", source)
        requireNotNull(result) { "expected an unresolved-variable result" }
        assertEquals("totallyUndefined", source.substring(result.offset, result.offset + "totallyUndefined".length))
        assertTrue(result.message.contains("totallyUndefined"), "message should mention the name: ${result.message}")
    }

    @Test
    fun `function-sugar bind parameter resolves inside its own body`() {
        // Regression guard, from the resolver's own perspective: real sjsonnet
        // must accept this (the exact construct JsonnetResolver.resolveLocalName
        // was found missing a case for — see AGENTS.md's Resolver bugs section).
        val result = SjsonnetStaticCheck.firstUnresolvedVariable(
            "test.jsonnet",
            "local f(x) = x + 1; f(2)",
        )
        assertNull(result)
    }

    @Test
    fun `object-scoped local is visible to a field value`() {
        val result = SjsonnetStaticCheck.firstUnresolvedVariable(
            "test.jsonnet",
            "{ local secret = 1, y: secret }",
        )
        assertNull(result)
    }

    @Test
    fun `unresolved name nested inside an object field is still caught`() {
        val source = "{ a: 1, b: nope }"
        val result = SjsonnetStaticCheck.firstUnresolvedVariable("test.jsonnet", source)
        requireNotNull(result) { "expected an unresolved-variable result" }
        assertEquals("nope", source.substring(result.offset, result.offset + "nope".length))
    }

    @Test
    fun `std is a known global, never flagged`() {
        val result = SjsonnetStaticCheck.firstUnresolvedVariable("test.jsonnet", "std.length([1, 2, 3])")
        assertNull(result)
    }

    @Test
    fun `a syntax error yields null, not a thrown exception`() {
        val result = SjsonnetStaticCheck.firstUnresolvedVariable("bad.jsonnet", "{ a: ")
        assertNull(result)
    }
}
