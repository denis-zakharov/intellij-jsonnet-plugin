package io.github.denis_zakharov.jsonnettanka.fmt

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `Options.WHITESPACE_ONLY` has no oracle (jsonnetfmt has no such mode), so it is checked as a property: the output has
 * exactly the input's non-whitespace characters. `JsonnetFormattingService` promises that to callers with
 * `canChangeWhiteSpaceOnly`, and relies on this holding except for the digit-separator case it guards itself.
 *
 * Always runs over the checked-in test inputs; `JSONNETFMT_CORPUS` (same variable as the differential test) adds more.
 */
class JsonnetFormatterWhitespaceOnlyTest {
    private fun corpus(): List<File> {
        val roots = listOf("src/test/resources/fmt") +
            System.getenv("JSONNETFMT_CORPUS").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        return roots.flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && (it.name.endsWith(".jsonnet") || it.name.endsWith(".libsonnet")) }.toList()
        }.sortedBy { it.path }
    }

    private fun stripWhitespace(s: String) = s.filterNot { it.isWhitespace() }

    /**
     * The three canonicalizations that come with lexing/unparsing itself (go-jsonnet does the same) and that no option
     * turns off; `JsonnetFormattingService` refuses a whitespace-only request whose result contains any of them.
     * Applied to both sides, whitespace already removed.
     */
    private fun normalize(s: String) = s
        .replace(Regex("(?<=\\d)_(?=\\d)"), "") // digit separators: 1_000 -> 1000
        .replace(Regex(":+(?=])"), "") // an empty slice step: a[1::] -> a[1:]
        .replace("|||-", "|||") // a text block's chomp marker, dropped when the text ends the same way

    private fun firstDifference(expected: String, actual: String): String {
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        fun around(s: String) = s.substring(maxOf(0, i - 15), minOf(s.length, i + 25))
        return "input |${around(expected)}| output |${around(actual)}|"
    }

    @Test
    fun `whitespace-only output differs from the input only in whitespace`() {
        val files = corpus()
        check(files.isNotEmpty())
        val failures = mutableListOf<String>()
        var formatted = 0
        for (file in files) {
            val text = file.readText()
            val out = try {
                JsonnetFormatter.format(text, Options.WHITESPACE_ONLY)
            } catch (_: ParseError) {
                continue // not valid Jsonnet (or a parse-error golden)
            }
            formatted++
            val expected = normalize(stripWhitespace(text))
            val actual = normalize(stripWhitespace(out))
            if (actual != expected) failures.add("${file.path}: ${firstDifference(expected, actual)}")
        }
        check(formatted > 0)
        if (failures.isNotEmpty()) fail<Unit>("${failures.size} files changed more than whitespace:\n" + failures.take(25).joinToString("\n"))
    }
}
