package io.github.denis_zakharov.jsonnettanka.stdlib

import io.github.denis_zakharov.jsonnettanka.engine.JsonnetEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SjsonnetOnlyStdTest {

    @Test
    fun `every flagged name is a real std member in the preview engine`() {
        val missing = SjsonnetOnlyStd.names.filter { it !in StdLibRegistry.memberNames }
        assertTrue(missing.isEmpty(), "no longer in sjsonnet's std, drop from SjsonnetOnlyStd: $missing")
    }

    @Test
    fun `the flagged functions really evaluate in the preview`() {
        // The premise of the inspection: this code previews fine (and only fails under tk).
        val result = JsonnetEngine.evaluate("probe.jsonnet", "std.regexQuoteMeta('a.b')")
        val output = (result as JsonnetEngine.Result.Success).output
        assertEquals("\"a\\\\.b\"", output.trim())
    }

    @Test
    fun `every entry names a portable alternative`() {
        for (entry in SjsonnetOnlyStd.entries) assertTrue(entry.portable.isNotBlank(), entry.name)
    }
}
