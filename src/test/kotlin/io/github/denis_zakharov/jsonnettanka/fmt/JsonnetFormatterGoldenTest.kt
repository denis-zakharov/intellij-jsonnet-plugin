package io.github.denis_zakharov.jsonnettanka.fmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Runs the port over the formatter goldens shipped with go-jsonnet v0.22.0 and its C++ jsonnet submodule
 * (Apache-2.0, see THIRD_PARTY_NOTICES.md), copied to `src/test/resources/fmt/upstream`. Both formatters produce
 * the same output for these, so a green run here means the port agrees with `jsonnetfmt`.
 */
class JsonnetFormatterGoldenTest {
    private val dir = File("src/test/resources/fmt/upstream")

    /** Cases written for this port; the goldens are `jsonnetfmt` v0.22.0's own output (default options). */
    private val oracleDir = File("src/test/resources/fmt/oracle")

    /** `<file>:<line>:<col>[-<col>] <file>:<line>:<col>[-<col>] <message>` */
    private val ERROR_GOLDEN = Regex("""^\S+:(\d+):(\d+)(?:-\d+)? \S+:\d+:\d+(?:-\d+)? (.*)$""", RegexOption.DOT_MATCHES_ALL)

    @TestFactory
    fun `upstream goldens`(): List<DynamicTest> {
        val inputs = dir.listFiles { f -> f.name.endsWith(".jsonnet") }!!.sortedBy { it.name }
        check(inputs.isNotEmpty()) { "no goldens found in ${dir.absolutePath}" }
        return inputs.map { input ->
            DynamicTest.dynamicTest(input.name) {
                val golden = File(dir, input.name.removeSuffix(".jsonnet") + ".fmt.golden").readText()
                val error = ERROR_GOLDEN.matchEntire(golden)
                if (error == null) {
                    assertEquals(golden, JsonnetFormatter.format(input.readText()))
                } else {
                    // go-jsonnet's test writes the error text into the golden when the input doesn't parse.
                    val e = assertThrows(ParseError::class.java) { JsonnetFormatter.format(input.readText()) }
                    assertEquals(error.groupValues[1].toInt(), e.line, "line of $e")
                    assertEquals(error.groupValues[2].toInt(), e.column, "column of $e")
                    assertTrue(e.message!!.contains(error.groupValues[3]), "message of $e")
                }
            }
        }
    }

    @TestFactory
    fun `jsonnetfmt output for hand-written cases`(): List<DynamicTest> {
        val inputs = oracleDir.listFiles { f -> f.name.endsWith(".jsonnet") }!!.sortedBy { it.name }
        check(inputs.isNotEmpty()) { "no cases found in ${oracleDir.absolutePath}" }
        return inputs.map { input ->
            DynamicTest.dynamicTest(input.name) {
                val golden = File(oracleDir, input.name.removeSuffix(".jsonnet") + ".fmt.golden").readText()
                assertEquals(golden, JsonnetFormatter.format(input.readText()))
            }
        }
    }

    @Test
    fun `formatting is idempotent on the goldens`() {
        // Upstream goldens only: jsonnetfmt itself is not idempotent everywhere (`(((1)))` loses one paren pair per run).
        for (golden in dir.listFiles { f -> f.name.endsWith(".fmt.golden") }!!.sortedBy { it.name }) {
            val text = golden.readText()
            if (ERROR_GOLDEN.matches(text)) continue
            assertEquals(text, JsonnetFormatter.format(text), "second pass changed ${golden.name}")
        }
    }
}
