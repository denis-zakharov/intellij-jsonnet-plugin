package com.dz.intellijjsonnet.editor.preview

import com.dz.intellijjsonnet.engine.JsonnetEngine
import com.dz.intellijjsonnet.engine.JsonnetEngine.OutputFormat
import com.dz.intellijjsonnet.engine.PathSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PreviewOutputPathsTest {

    private val source = """
        {
          spec: {
            name: 'a "quoted" \\ name',
            containers: [{ name: 'web', ports: [80, 443] }, { name: 'sidecar' }],
            empty: {},
            none: [],
            note: 'multi\nline',
          },
        }
    """.trimIndent()

    private fun render(format: OutputFormat): String {
        val result = JsonnetEngine.evaluate("t.jsonnet", source, format = format)
        require(result is JsonnetEngine.Result.Success) { "got $result" }
        return result.output
    }

    private fun k(vararg names: Any): List<PathSegment> =
        names.map { if (it is Int) PathSegment.Index(it) else PathSegment.Key(it as String) }

    /** Path for the first line of [text] containing [needle]. */
    private fun pathOf(text: String, format: OutputFormat, needle: String, nth: Int = 0): List<PathSegment>? {
        val line = text.lines().withIndex().filter { needle in it.value }.map { it.index }[nth]
        return PreviewOutputPaths.pathForLine(text, format, line)
    }

    @Test
    fun `json lines map to the innermost entry they start`() {
        val json = render(OutputFormat.JSON)
        assertEquals(k("spec"), pathOf(json, OutputFormat.JSON, "\"spec\""))
        assertEquals(k("spec", "containers"), pathOf(json, OutputFormat.JSON, "\"containers\""))
        assertEquals(k("spec", "containers", 0, "name"), pathOf(json, OutputFormat.JSON, "\"web\""))
        assertEquals(k("spec", "containers", 0, "ports"), pathOf(json, OutputFormat.JSON, "\"ports\""))
        assertEquals(k("spec", "containers", 0, "ports", 0), pathOf(json, OutputFormat.JSON, "80"))
        assertEquals(k("spec", "containers", 0, "ports", 1), pathOf(json, OutputFormat.JSON, "443"))
        assertEquals(k("spec", "containers", 1, "name"), pathOf(json, OutputFormat.JSON, "\"sidecar\""))
    }

    @Test
    fun `json string values with escapes and multi-line strings do not derail the scan`() {
        val json = render(OutputFormat.JSON)
        assertEquals(k("spec", "name"), pathOf(json, OutputFormat.JSON, "quoted"))
        assertEquals(k("spec", "note"), pathOf(json, OutputFormat.JSON, "multi"))
    }

    @Test
    fun `json closing brackets belong to the container they close`() {
        val json = "{\n  \"a\": [\n    1\n  ],\n  \"b\": 2\n}"
        assertEquals(k("a"), PreviewOutputPaths.pathForLine(json, OutputFormat.JSON, 3))
        assertEquals(emptyList<PathSegment>(), PreviewOutputPaths.pathForLine(json, OutputFormat.JSON, 5))
    }

    @Test
    fun `yaml lines map to the innermost entry they start, including dash-key lines`() {
        val yaml = render(OutputFormat.YAML)
        assertEquals(k("spec"), pathOf(yaml, OutputFormat.YAML, "spec:"))
        assertEquals(k("spec", "containers"), pathOf(yaml, OutputFormat.YAML, "containers:"))
        assertEquals(k("spec", "containers", 0, "name"), pathOf(yaml, OutputFormat.YAML, "web"))
        assertEquals(k("spec", "containers", 0, "ports", 1), pathOf(yaml, OutputFormat.YAML, "443"))
        assertEquals(k("spec", "containers", 1, "name"), pathOf(yaml, OutputFormat.YAML, "sidecar"))
    }

    @Test
    fun `a line that starts no entry falls back to the nearest preceding one`() {
        val yaml = "a:\n  b: |\n    first\n    second\nc: 1"
        assertEquals(k("a", "b"), PreviewOutputPaths.pathForLine(yaml, OutputFormat.YAML, 3))
        assertEquals(k("c"), PreviewOutputPaths.pathForLine(yaml, OutputFormat.YAML, 4))
    }

    @Test
    fun `text that is not output maps to nothing`() {
        assertNull(PreviewOutputPaths.pathForLine("Evaluation failed:\nboom: {{", OutputFormat.YAML, 1))
        assertNull(PreviewOutputPaths.pathForLine("", OutputFormat.JSON, 0))
    }
}
