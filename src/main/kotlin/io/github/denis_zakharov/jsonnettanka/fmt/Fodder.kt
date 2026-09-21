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

Ported to Kotlin from go-jsonnet v0.22.0 (ast/fodder.go, commit 567b61a); modified.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/**
 * Fodder is whatever a lexer would normally throw away — whitespace and comments — kept so the source
 * round-trips. It hangs off the token that *follows* it.
 */
enum class FodderKind {
    /** Next token / paragraph / interstitial goes on a new line. May carry one trailing comment. */
    LINE_END,

    /** A `/* c-style */` comment in the middle of a line. Exactly one comment string. */
    INTERSTITIAL,

    /** A comment of one or more lines on its own line(s). */
    PARAGRAPH,
}

class FodderElement(
    val kind: FodderKind,
    var blanks: Int,
    var indent: Int,
    var comment: MutableList<String>,
) {
    init {
        require(!(kind == FodderKind.LINE_END && comment.size > 1)) { "LINE_END fodder with comment $comment" }
        require(!(kind == FodderKind.INTERSTITIAL && blanks > 0)) { "INTERSTITIAL fodder with blanks $blanks" }
        require(!(kind == FodderKind.INTERSTITIAL && indent > 0)) { "INTERSTITIAL fodder with indent $indent" }
        require(!(kind == FodderKind.INTERSTITIAL && comment.size != 1)) { "INTERSTITIAL fodder with comment $comment" }
        require(!(kind == FodderKind.PARAGRAPH && comment.isEmpty())) { "PARAGRAPH fodder with no comment" }
    }

    fun copy() = FodderElement(kind, blanks, indent, comment.toMutableList())

    /** Number of `\n` characters this element stands for. */
    fun countNewlines(): Int = when (kind) {
        FodderKind.INTERSTITIAL -> 0
        FodderKind.LINE_END -> 1
        FodderKind.PARAGRAPH -> comment.size + blanks
    }
}

/**
 * A mutable holder so passes can rewrite a node's fodder in place (Go passes `*Fodder`). Every node field
 * owns its own [Fodder] instance; never share one between two fields.
 */
class Fodder(var elements: MutableList<FodderElement> = mutableListOf()) {
    val size: Int get() = elements.size
    fun isEmpty(): Boolean = elements.isEmpty()
    fun isNotEmpty(): Boolean = elements.isNotEmpty()

    fun copy() = Fodder(elements.mapTo(mutableListOf()) { it.copy() })
}

fun fodderHasCleanEndline(f: Fodder): Boolean =
    f.elements.isNotEmpty() && f.elements.last().kind != FodderKind.INTERSTITIAL

/** Appends while preserving the fodder invariants (see [fodderConcat]). */
fun fodderAppend(a: Fodder, elem: FodderElement) {
    if (fodderHasCleanEndline(a) && elem.kind == FodderKind.LINE_END) {
        if (elem.comment.isNotEmpty()) {
            // The line end had a comment, so create a single line paragraph for it.
            a.elements.add(FodderElement(FodderKind.PARAGRAPH, elem.blanks, elem.indent, elem.comment))
        } else {
            // Merge it into the previous line end.
            val back = a.elements.last()
            back.indent = elem.indent
            back.blanks += elem.blanks
        }
    } else {
        if (!fodderHasCleanEndline(a) && elem.kind == FodderKind.PARAGRAPH) {
            a.elements.add(FodderElement(FodderKind.LINE_END, 0, elem.indent, mutableListOf()))
        }
        a.elements.add(elem)
    }
}

/** Concatenates two fodders; a LINE_END may not follow a PARAGRAPH or another LINE_END. Consumes both. */
fun fodderConcat(a: Fodder, b: Fodder): Fodder {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val r = Fodder(a.elements.toMutableList())
    fodderAppend(r, b.elements[0])
    for (i in 1 until b.elements.size) r.elements.add(b.elements[i])
    return r
}

/** Moves [b] to the front of [a], leaving [b] empty. */
fun fodderMoveFront(a: Fodder, b: Fodder) {
    a.elements = fodderConcat(b, a).elements
    b.elements = mutableListOf()
}

fun fodderEnsureCleanNewline(f: Fodder) {
    if (!fodderHasCleanEndline(f)) {
        fodderAppend(f, FodderElement(FodderKind.LINE_END, 0, 0, mutableListOf()))
    }
}

fun fodderCountNewlines(f: Fodder): Int = f.elements.sumOf { it.countNewlines() }
