package io.github.denis_zakharov.jsonnettanka.fmt

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Opt-in: compares the port against a real `jsonnetfmt` binary over a corpus of `.jsonnet`/`.libsonnet` files.
 *
 *     JSONNETFMT_BIN=~/go/bin/jsonnetfmt JSONNETFMT_CORPUS=/path/a:/path/b ./gradlew test --tests '*Differential*'
 *
 * Rather than calling this directly, use `scripts/jsonnetfmt-conformance.py differential`, which also checks that the
 * binary is the release the port is pinned to ([PortedFrom]).
 *
 * `JSONNETFMT_BIN` defaults to `jsonnetfmt` on PATH; `JSONNETFMT_VARIANTS=all` also runs every option variant below
 * (default is the default options only). Files the binary rejects must be rejected by the port too. Corpora worth
 * running after any change to `fmt/`: a go-jsonnet checkout, a sparse k8s-libsonnet clone, and your own Tanka projects.
 */
class JsonnetFormatterDifferentialTest {
    private class Variant(val name: String, val flags: List<String>, val options: Options)

    private val variants = listOf(
        Variant("default", emptyList(), Options.DEFAULT),
        Variant("indent 4, one blank line", listOf("-n", "4", "--max-blank-lines", "1"), Options(indent = 4, maxBlankLines = 1)),
        Variant(
            "double quotes, hash comments",
            listOf("--string-style", "d", "--comment-style", "h"),
            Options(stringStyle = StringStyle.DOUBLE, commentStyle = CommentStyle.HASH),
        ),
        Variant(
            "leave quotes and comments",
            listOf("--string-style", "l", "--comment-style", "l"),
            Options(stringStyle = StringStyle.LEAVE, commentStyle = CommentStyle.LEAVE),
        ),
        Variant(
            "no pretty names, pad arrays, no pad objects",
            listOf("--no-pretty-field-names", "--pad-arrays", "--no-pad-objects"),
            Options(prettyFieldNames = false, padArrays = true, padObjects = false),
        ),
        Variant(
            "explicit plus, no import sorting",
            listOf("--no-use-implicit-plus", "--no-sort-imports"),
            Options(useImplicitPlus = false, sortImports = false),
        ),
        Variant("no reindent, unlimited blank lines", listOf("-n", "0", "--max-blank-lines", "0"), Options(indent = 0, maxBlankLines = 0)),
        Variant("indent 8, pad arrays and objects", listOf("-n", "8", "--pad-arrays"), Options(indent = 8, padArrays = true)),
        Variant("indent 1, three blank lines", listOf("-n", "1", "--max-blank-lines", "3"), Options(indent = 1, maxBlankLines = 3)),
        Variant(
            "single quotes, slash comments, no pad objects, explicit plus",
            listOf("--string-style", "s", "--comment-style", "s", "--no-pad-objects", "--no-use-implicit-plus"),
            Options(stringStyle = StringStyle.SINGLE, commentStyle = CommentStyle.SLASH, padObjects = false, useImplicitPlus = false),
        ),
    )

    private class Mismatch(val variant: String, val file: File, val detail: String)

    private fun oracle(bin: String, flags: List<String>, file: File): String? {
        val p = ProcessBuilder(listOf(bin) + flags + listOf("--", file.absolutePath)).start()
        var out = ""
        val reader = Thread { out = p.inputStream.readBytes().toString(Charsets.UTF_8) }.also { it.start() }
        p.errorStream.readBytes()
        reader.join()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "jsonnetfmt timed out on $file" }
        return if (p.exitValue() == 0) out else null
    }

    private fun firstDifference(expected: String, actual: String): String {
        val e = expected.split('\n')
        val a = actual.split('\n')
        for (i in 0 until maxOf(e.size, a.size)) {
            if (e.getOrNull(i) != a.getOrNull(i)) {
                return "line ${i + 1}:\n  jsonnetfmt: ${e.getOrNull(i)?.let { "|$it|" }}\n  port:       ${a.getOrNull(i)?.let { "|$it|" }}"
            }
        }
        return "identical lines, different endings"
    }

    @Test
    fun `port matches jsonnetfmt on the corpus`() {
        val corpus = System.getenv("JSONNETFMT_CORPUS")
        assumeTrue(!corpus.isNullOrBlank(), "set JSONNETFMT_CORPUS to run")
        val bin = System.getenv("JSONNETFMT_BIN") ?: "jsonnetfmt"
        val active = if (System.getenv("JSONNETFMT_VARIANTS") == "all") variants else variants.take(1)

        val allFiles = corpus!!.split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .flatMap { root ->
                File(root).walkTopDown()
                    .filter { it.isFile && (it.name.endsWith(".jsonnet") || it.name.endsWith(".libsonnet")) }
                    .toList()
            }
            .sortedBy { it.path }
        assumeTrue(allFiles.isNotEmpty(), "no files under $corpus")
        val files = allFiles

        val mismatches = Collections.synchronizedList(mutableListOf<Mismatch>())
        for (variant in active) {
            var same = 0
            var rejectedByBoth = 0
            val counters = java.util.concurrent.atomic.AtomicIntegerArray(2)
            files.parallelStream().forEach { file ->
                val expected = oracle(bin, variant.flags, file)
                val actual = try {
                    JsonnetFormatter.format(file.readText(), variant.options)
                } catch (e: ParseError) {
                    null
                } catch (e: Throwable) {
                    mismatches.add(Mismatch(variant.name, file, "port crashed: $e"))
                    return@forEach
                }
                when {
                    expected == null && actual == null -> counters.incrementAndGet(1)
                    expected == null -> mismatches.add(Mismatch(variant.name, file, "jsonnetfmt rejects it, the port formats it"))
                    actual == null -> mismatches.add(Mismatch(variant.name, file, "jsonnetfmt formats it, the port rejects it"))
                    expected == actual -> counters.incrementAndGet(0)
                    else -> mismatches.add(Mismatch(variant.name, file, firstDifference(expected, actual)))
                }
            }
            same = counters.get(0)
            rejectedByBoth = counters.get(1)
            println("differential [${variant.name}]: ${files.size} files, $same identical, $rejectedByBoth rejected by both")
        }
        println("differential: ${mismatches.size} mismatches")
        if (mismatches.isNotEmpty()) {
            fail<Unit>(
                "${mismatches.size} mismatches against jsonnetfmt:\n" +
                    mismatches.sortedBy { it.file.path }.take(25).joinToString("\n") { "* [${it.variant}] ${it.file}: ${it.detail}" },
            )
        }
    }
}
