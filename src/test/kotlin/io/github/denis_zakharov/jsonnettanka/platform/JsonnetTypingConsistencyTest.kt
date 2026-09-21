package io.github.denis_zakharov.jsonnettanka.platform

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.denis_zakharov.jsonnettanka.fmt.JsonnetFormatter
import io.github.denis_zakharov.jsonnettanka.fmt.ParseError
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import java.io.File

/**
 * The check that keeps the typing side (Block model) honest against the Reformat Code side (`jsonnetfmt` port): for
 * every jsonnetfmt-canonical text, strip one line's indent, ask `CodeStyleManager.adjustLineIndent` (the on-type path)
 * for it back and compare. Lines starting inside a comment/text block or with a comment are skipped: comments are
 * indented by whatever follows them, which a per-line question cannot know.
 *
 * Corpus: the upstream goldens and hand-written oracle cases (always), plus every `.jsonnet`/`.libsonnet` below the
 * directories in `JSONNET_TYPING_CORPUS` (path-separator separated, opt-in — e.g. a Tanka project or k8s-libsonnet).
 * Mismatches are written to `build/typing-consistency.txt`.
 */
class JsonnetTypingConsistencyTest : BasePlatformTestCase() {
    private class Stats {
        var lines = 0
        var matched = 0
        val misses = mutableListOf<String>()
    }

    private fun corpus(): List<Pair<String, String>> {
        val texts = mutableListOf<Pair<String, String>>()
        for (dir in listOf("src/test/resources/fmt/upstream", "src/test/resources/fmt/oracle")) {
            File(dir).listFiles { f -> f.name.endsWith(".jsonnet") }!!.sortedBy { it.name }.forEach {
                canonical(it.readText())?.let { text -> texts += "${it.name}" to text }
            }
        }
        System.getenv("JSONNET_TYPING_CORPUS")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.forEach { root ->
            File(root).walkTopDown().filter { it.isFile && (it.extension == "jsonnet" || it.extension == "libsonnet") }
                .sortedBy { it.path }.forEach { f -> canonical(f.readText())?.let { texts += f.path to it } }
        }
        return texts
    }

    private fun canonical(text: String): String? = try {
        JsonnetFormatter.format(text).takeIf { !it.contains('\r') }
    } catch (e: ParseError) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun checkFile(name: String, text: String, stats: Stats) {
        myFixture.configureByText("t.jsonnet", text)
        val file = myFixture.file
        if (PsiTreeUtil.hasErrorElements(file)) return
        val document = myFixture.editor.document
        val lineCount = document.lineCount
        for (line in 0 until lineCount) {
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val content = text.substring(start, end)
            if (content.isBlank()) continue
            val indent = content.length - content.trimStart().length
            if (indent == 0) continue // nothing to strip; column 0 is what a missing indent would give too
            if (!isChecked(file, start, start + indent)) continue

            WriteCommandAction.runWriteCommandAction(project) { document.deleteString(start, start + indent) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
            WriteCommandAction.runWriteCommandAction(project) {
                CodeStyleManager.getInstance(project).adjustLineIndent(file, start)
            }
            val actual = document.getText(com.intellij.openapi.util.TextRange(start, document.getLineEndOffset(line)))
            val actualIndent = actual.length - actual.trimStart().length
            stats.lines++
            if (actualIndent == indent) {
                stats.matched++
            } else {
                stats.misses += "$name:${line + 1}: want $indent, got $actualIndent | ${content.trim().take(70)}"
            }
            WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    /** Is the line whose leading whitespace is `[from, to)` one worth asking about (code, not comment/text-block)? */
    private fun isChecked(file: PsiFile, from: Int, to: Int): Boolean {
        val first = file.findElementAt(to) ?: return false
        val type = first.node.elementType
        if (type == JsonnetTypes.COMMENT) return false
        // The line starts inside a multi-line token (text block, block comment).
        val at = file.findElementAt(from)
        if (at != null && at.node.elementType != com.intellij.psi.TokenType.WHITE_SPACE && at.textRange.startOffset < from) return false
        return true
    }

    fun `test the block model agrees with jsonnetfmt on canonical files`() {
        val stats = Stats()
        for ((name, text) in corpus()) checkFile(name, text, stats)
        val rate = if (stats.lines == 0) 0.0 else 100.0 * stats.matched / stats.lines
        File("build").mkdirs()
        File("build/typing-consistency.txt").writeText(
            "matched ${stats.matched}/${stats.lines} (%.1f%%)\n".format(rate) + stats.misses.joinToString("\n") + "\n",
        )
        println("typing consistency: ${stats.matched}/${stats.lines} (%.1f%%)".format(rate))
        assertTrue("no lines were checked", stats.lines > 0)
        assertTrue(
            "match rate %.1f%% below the floor; see build/typing-consistency.txt (first misses: %s)".format(rate, stats.misses.take(8)),
            rate >= MIN_RATE,
        )
    }

    private companion object {
        /** Ratchet: raise it when the rules improve, never lower it without documenting the shape that regressed. */
        const val MIN_RATE = 95.0
    }
}
