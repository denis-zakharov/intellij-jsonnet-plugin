package com.dz.intellijjsonnet.platform

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * The hover at the fixture's caret, as plain text, or `null` when the platform offers none. Goes through
 * `IdeDocumentationTargetProvider` — the real path from a caret to a `DocumentationProvider` — because
 * calling a provider directly would skip the step where the platform decides *what* is under the caret.
 *
 * Tags are stripped and entities decoded, so assertions read like the rendered popup; the highlighter emits
 * spaces as `&#32;`, which `unescapeXmlEntities` doesn't know.
 */
internal fun hoverAtCaret(fixture: CodeInsightTestFixture): String? {
    val targets = IdeDocumentationTargetProvider.getInstance(fixture.project)
        .documentationTargets(fixture.editor, fixture.file, fixture.caretOffset)
    val html = targets.firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html } ?: return null
    val withoutTags = html.replace(Regex("<[^>]+>"), "")
    return StringUtil.unescapeXmlEntities(withoutTags.replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() })
}
