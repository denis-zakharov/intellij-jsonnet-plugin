package com.dz.intellijjsonnet.editor.preview

import com.dz.intellijjsonnet.engine.JsonnetEngine.OutputFormat
import com.dz.intellijjsonnet.engine.PathSegment
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.Yaml
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.nodes.MappingNode
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.nodes.Node
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.nodes.ScalarNode
import com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader

/**
 * The output half of Preview's click-jump: maps a line of rendered JSON/YAML to the path of the
 * value it shows (`[Key("spec"), Key("containers"), Index(0), Key("name")]`), which
 * [com.dz.intellijjsonnet.engine.SourceLocator] then resolves to a source position.
 *
 * Line-based on purpose — it's what a click gives you and it's predictable: a line belongs to the
 * innermost entry that starts on it (`- name: x` is the `name` key of that sequence item; a closing
 * `}`/`]` belongs to the container it closes). Lines that start no entry (blank lines, the middle
 * of a multi-line YAML string) fall back to the nearest preceding line that does.
 */
object PreviewOutputPaths {

    /** Path for the 0-based [line] of [text] rendered as [format], or `null` if nothing maps (unparseable output, empty text). */
    fun pathForLine(text: String, format: OutputFormat, line: Int): List<PathSegment>? {
        val byLine = try {
            when (format) {
                OutputFormat.JSON -> jsonLinePaths(text)
                OutputFormat.YAML -> yamlLinePaths(text)
            }
        } catch (e: Exception) {
            return null // e.g. the panel is showing an "Evaluation failed" message, not output
        }
        var l = line
        while (l >= 0) {
            byLine[l]?.let { return it }
            l--
        }
        return null
    }

    // --- JSON: a tiny tokenizer over ujson's well-formed pretty-printed output ---

    private class Frame(val isObject: Boolean, val path: List<PathSegment>) {
        var key: String? = null
        var index = 0
        var expectKey = isObject
        fun entryPath(): List<PathSegment> =
            if (isObject) path + PathSegment.Key(key ?: "") else path + PathSegment.Index(index)
    }

    private fun jsonLinePaths(text: String): Map<Int, List<PathSegment>> {
        val byLine = HashMap<Int, List<PathSegment>>()
        val stack = ArrayList<Frame>()
        var line = 0
        var i = 0

        fun entryPath(): List<PathSegment> = stack.lastOrNull()?.entryPath() ?: emptyList()

        while (i < text.length) {
            when (val c = text[i]) {
                '\n' -> line++
                '"' -> {
                    val start = i
                    i++
                    val sb = StringBuilder()
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            i++
                            when (val esc = text[i]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'r' -> sb.append('\r')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'u' -> {
                                    sb.append(text.substring(i + 1, minOf(i + 5, text.length)).toIntOrNull(16)?.toChar() ?: '?')
                                    i += 4
                                }
                                else -> sb.append(esc)
                            }
                        } else {
                            sb.append(text[i])
                        }
                        i++
                    }
                    val top = stack.lastOrNull()
                    if (top != null && top.isObject && top.expectKey) {
                        top.key = sb.toString()
                        top.expectKey = false
                    }
                    byLine[line] = entryPath()
                    check(start <= i)
                }
                '{', '[' -> {
                    val path = entryPath()
                    byLine[line] = path
                    stack.add(Frame(isObject = c == '{', path = path))
                }
                '}', ']' -> {
                    val frame = stack.removeLastOrNull()
                    byLine[line] = frame?.path ?: emptyList()
                }
                ',' -> stack.lastOrNull()?.let { if (it.isObject) it.expectKey = true else it.index++ }
                ' ', '\t', '\r', ':' -> {}
                else -> byLine[line] = entryPath() // number / true / false / null
            }
            i++
        }
        return byLine
    }

    // --- YAML: snakeyaml's node tree carries per-node line marks ---

    private fun yamlLinePaths(text: String): Map<Int, List<PathSegment>> {
        val byLine = HashMap<Int, List<PathSegment>>()
        val root = Yaml().compose(StringReader(text)) ?: return byLine

        fun visit(node: Node, path: List<PathSegment>) {
            when (node) {
                is MappingNode -> for (tuple in node.value) {
                    val key = (tuple.keyNode as? ScalarNode)?.value ?: continue
                    val entry = path + PathSegment.Key(key)
                    byLine[tuple.keyNode.startMark.line] = entry
                    visit(tuple.valueNode, entry)
                }
                is SequenceNode -> node.value.forEachIndexed { index, item ->
                    val entry = path + PathSegment.Index(index)
                    // Outer first, inner overwrites: `- name: x` ends up as the `name` key of item `index`.
                    byLine[item.startMark.line] = entry
                    visit(item, entry)
                }
                else -> {}
            }
        }
        visit(root, emptyList())
        return byLine
    }
}
