/*
Copyright 2019 Google Inc. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Ported to Kotlin from go-jsonnet v0.22.0 (internal/formatter/sort_imports.go, commit 567b61a); modified:
keys compare by code point (== UTF-8 byte order, as Go compares strings), and fodder elements are copied where Go
copies structs.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

private class ImportElem(val adjacentFodder: Fodder, val key: String, val bind: LocalBind)

/** Compares by Unicode code point, which orders exactly like the UTF-8 bytes Go's string comparison uses. */
private fun compareCodePoints(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i)
        val cb = b.codePointAt(j)
        if (ca != cb) return ca.compareTo(cb)
        i += Character.charCount(ca)
        j += Character.charCount(cb)
    }
    return (a.length - i).compareTo(b.length - j)
}

private fun duplicatedVariables(elems: List<ImportElem>): Boolean =
    elems.mapTo(HashSet()) { it.bind.variable }.size < elems.size

private fun sortGroup(imports: MutableList<ImportElem>) {
    if (!duplicatedVariables(imports)) {
        // Go's sort.Slice is not stable, but for the group sizes seen in practice (<= 12) it is insertion sort.
        val sorted = imports.sortedWith { x, y -> compareCodePoints(x.key, y.key) }
        imports.clear()
        imports.addAll(sorted)
    }
}

/** Checks that a `local` expression is used only for importing. */
private fun isGoodLocal(local: Local): Boolean {
    for (bind in local.binds) {
        if (bind.fn != null) return false
        if (bind.body !is Import) return false
    }
    return true
}

private fun goodLocalOrNull(node: Node): Local? = if (node is Local && isGoodLocal(node)) node else null

/**
 * Splits fodder after the first new line / paragraph fodder, leaving blank lines after the newline in the second
 * half. The two results can be concatenated to get the original fodder.
 *
 * A heuristic deciding, for two consecutive tokens `prev_token`, `next_token` with fodder between them, which part
 * of the fodder logically belongs to which:
 *
 *     prev_token // prev_token is awesome!
 *
 *     // blah blah
 *     next_token
 *
 * Here "// prev_token is awesome!\n" belongs to `prev_token` and "\n//blah blah\n" to `next_token`.
 */
private fun splitFodder(fodder: Fodder): Pair<Fodder, Fodder> {
    val afterPrev = Fodder()
    val beforeNext = Fodder()
    var inSecondPart = false
    for (original in fodder.elements) {
        val fodderElem = original.copy()
        if (inSecondPart) {
            fodderAppend(beforeNext, fodderElem)
        } else {
            afterPrev.elements.add(fodderElem)
        }
        if (fodderElem.kind != FodderKind.INTERSTITIAL && !inSecondPart) {
            inSecondPart = true
            if (fodderElem.blanks > 0) {
                // If there are any blank lines at the end of afterPrev, move them to beforeNext.
                val blanks = fodderElem.blanks
                val indent = fodderElem.indent
                afterPrev.elements.last().blanks = 0
                check(beforeNext.isEmpty()) { "beforeNext should still be empty." }
                beforeNext.elements.add(FodderElement(FodderKind.LINE_END, blanks, indent, mutableListOf()))
            }
        }
    }
    return afterPrev to beforeNext
}

private fun extractImportElems(binds: List<LocalBind>, after: Fodder): List<ImportElem> {
    val result = mutableListOf<ImportElem>()
    var before = binds[0].varFodder
    for ((i, bind) in binds.withIndex()) {
        val last = i == binds.size - 1
        val adjacent: Fodder
        var beforeNext = Fodder()
        if (!last) {
            val split = splitFodder(binds[i + 1].varFodder)
            adjacent = split.first
            beforeNext = split.second
        } else {
            adjacent = after
        }
        fodderEnsureCleanNewline(adjacent)
        val newBind = LocalBind(before, bind.variable, bind.eqFodder, bind.body, bind.closeFodder, bind.fn)
        val theImport = bind.body as Import
        result.add(ImportElem(adjacent, theImport.file.value, newBind))
        before = beforeNext
    }
    return result
}

private fun buildGroupAst(imports: List<ImportElem>, bodyIn: Node, groupOpenFodder: Fodder): Node {
    var body = bodyIn
    for (i in imports.indices.reversed()) {
        val fodder = if (i == 0) groupOpenFodder else imports[i - 1].adjacentFodder
        body = Local(mutableListOf(imports[i].bind), body, fodder)
    }
    return body
}

private fun groupEndsAfter(local: Local): Boolean {
    val next = goodLocalOrNull(local.body) ?: return true
    var newlineReached = false
    for (fodderElem in openFodder(next).elements) {
        if (newlineReached || fodderElem.blanks > 0) return true
        if (fodderElem.kind != FodderKind.INTERSTITIAL) newlineReached = true
    }
    return false
}

private fun topLevelImport(local: Local, imports: MutableList<ImportElem>, groupOpenFodder: Fodder): Node {
    check(isGoodLocal(local)) { "topLevelImport called with bad local." }
    val (adjacentCommentFodder, beforeNextFodder) = splitFodder(openFodder(local.body))
    fodderEnsureCleanNewline(adjacentCommentFodder)
    imports.addAll(extractImportElems(local.binds, adjacentCommentFodder))

    if (groupEndsAfter(local)) {
        sortGroup(imports)
        val afterGroup = imports.last().adjacentFodder
        fodderEnsureCleanNewline(beforeNextFodder)
        val nextOpenFodder = fodderConcat(afterGroup, beforeNextFodder)
        val bodyAfterGroup: Node
        // Process the code after the current group:
        val next = goodLocalOrNull(local.body)
        if (next != null) {
            // Another group of imports
            bodyAfterGroup = topLevelImport(next, mutableListOf(), nextOpenFodder)
        } else {
            // Something else
            bodyAfterGroup = local.body
            openFodder(bodyAfterGroup).elements = nextOpenFodder.elements
        }
        return buildGroupAst(imports, bodyAfterGroup, groupOpenFodder)
    }

    check(beforeNextFodder.isEmpty()) { "Expected beforeNextFodder to be empty" }
    return topLevelImport(local.body as Local, imports, groupOpenFodder)
}

/**
 * Sorts the imports at the top of the file into alphabetical order by path.
 *
 * Top-level imports are `local x = import 'xxx.jsonnet'` expressions that go before anything else in the file (all
 * such imports that are either the root of the AST or a direct child (body) of a top-level import). Groups of
 * imports are separated by blank lines or lines containing comments and stay separate.
 */
internal fun sortImports(file: Node): Node {
    val local = goodLocalOrNull(file) ?: return file
    return topLevelImport(local, mutableListOf(), local.fodder)
}
