package com.dz.intellijjsonnet.editor.preview

import com.dz.intellijjsonnet.engine.JsonnetEngine.VarValue

/**
 * Parses the Preview panel's ext-var / TLA-var boxes, one variable per line:
 *
 *  - `name=value`   — a string (`--ext-str` / `--tla-str`); everything after the first `=` is the value
 *  - `name:=code`   — Jsonnet code (`--ext-code` / `--tla-code`), e.g. `replicas:=3` or `tags:=['a']`
 *
 * Blank lines and lines starting with `#` are ignored, as are lines with no `=` or an empty name.
 * A later line for the same name wins.
 */
object PreviewVars {

    fun parse(text: String): Map<String, VarValue> {
        val result = LinkedHashMap<String, VarValue>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val isCode = line[eq - 1] == ':'
            val name = line.substring(0, if (isCode) eq - 1 else eq).trim()
            if (name.isEmpty()) continue
            result[name] = VarValue(line.substring(eq + 1).trim(), isCode)
        }
        return result
    }
}
