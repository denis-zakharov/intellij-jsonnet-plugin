package io.github.denis_zakharov.jsonnettanka.formatter

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AbstractDocumentFormattingService
import com.intellij.formatting.service.FormattingService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.denis_zakharov.jsonnettanka.fmt.JsonnetFormatter
import io.github.denis_zakharov.jsonnettanka.fmt.ParseError
import io.github.denis_zakharov.jsonnettanka.lang.JsonnetLanguage

/**
 * Reformat Code for Jsonnet: runs the [JsonnetFormatter] port, so the result is what `jsonnetfmt`/`tk fmt` would write.
 *
 * Registered without `AD_HOC_FORMATTING`, so only explicit requests (Reformat Code, reformat-on-save, "changed lines")
 * come here; paste, quick fixes and refactorings keep using the platform's Block-model formatter
 * ([JsonnetFormattingModelBuilder]), as does any file with syntax errors ([canFormat]).
 *
 * `jsonnetfmt` is whole-file only, so a selection is handled by formatting everything and applying just the edits
 * that touch the requested ranges. Edits are applied as a minimal diff, so carets, folds and bookmarks survive.
 */
class JsonnetFormattingService : AbstractDocumentFormattingService() {
    override fun getFeatures(): Set<FormattingService.Feature> = setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean =
        file.language.isKindOf(JsonnetLanguage) && !PsiTreeUtil.hasErrorElements(file)

    override fun formatDocument(
        document: Document,
        formattingRanges: List<TextRange>,
        formattingContext: FormattingContext,
        canChangeWhiteSpaceOnly: Boolean,
        quickFormat: Boolean,
    ) {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val custom = settings.getCustomSettings(JsonnetCodeStyleSettings::class.java)
        // Handled here (not in canFormat) on purpose: declining would hand the file to the Block-model formatter,
        // which would happily reformat it.
        if (custom.SKIP_VENDOR_AND_DOTFILES) {
            val path = file.originalFile.virtualFile?.path
            if (path != null && JsonnetFormatExclusions.isExcluded(path, file.project.basePath)) return
        }
        val indent = settings.getIndentOptionsByFile(file).INDENT_SIZE
        val options = if (canChangeWhiteSpaceOnly) custom.toWhitespaceOnlyOptions(indent) else custom.toOptions(indent)

        val original = document.text
        val formatted = try {
            JsonnetFormatter.format(original, options)
        } catch (e: ParseError) {
            NotificationGroupManager.getInstance().getNotificationGroup("Jsonnet")
                .createNotification("Can't format ${file.name}", e.message ?: "syntax error", NotificationType.WARNING)
                .notify(file.project)
            return
        }
        // "Whitespace only" is a promise to the caller; the lexer drops digit separators (1_000), so check it.
        if (canChangeWhiteSpaceOnly && !JsonnetTextEdits.sameApartFromWhitespace(original, formatted)) return

        val wholeFile = formattingRanges.size == 1 && formattingRanges[0] == TextRange(0, original.length)
        JsonnetTextEdits.apply(document, formatted, if (wholeFile) null else formattingRanges, file.project)
    }
}

/** Turning "the whole formatted text" into small document edits. */
internal object JsonnetTextEdits {
    class Edit(val start: Int, val end: Int, val replacement: String)

    /** Minimal edits (ascending, non-overlapping) turning [original] into [formatted]. */
    fun compute(original: String, formatted: String): List<Edit> {
        if (original == formatted) return emptyList()
        val fragments = ComparisonManager.getInstance()
            .compareLinesInner(original, formatted, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
        val edits = mutableListOf<Edit>()
        for (line in fragments) {
            val inner = line.innerFragments
            if (inner == null) {
                edits.add(Edit(line.startOffset1, line.endOffset1, formatted.substring(line.startOffset2, line.endOffset2)))
            } else {
                // Offsets of inner fragments are relative to the start of the line fragment.
                for (f in inner) {
                    edits.add(
                        Edit(
                            line.startOffset1 + f.startOffset1,
                            line.startOffset1 + f.endOffset1,
                            formatted.substring(line.startOffset2 + f.startOffset2, line.startOffset2 + f.endOffset2),
                        ),
                    )
                }
            }
        }
        return edits
    }

    /** Applies the formatting to [document]; with [onlyWithin] set, just the edits that touch one of those ranges. */
    fun apply(document: Document, formatted: String, onlyWithin: List<TextRange>?, project: Project? = null) {
        val original = document.text
        val edits = compute(original, formatted).filter { e ->
            onlyWithin == null || onlyWithin.any { r ->
                if (e.start == e.end) r.startOffset <= e.start && e.start <= r.endOffset else e.start < r.endOffset && r.startOffset < e.end
            }
        }
        // Carets are put back by hand: an insertion exactly at the caret would otherwise leave it in front of the
        // whitespace, i.e. no longer attached to the token it was in front of.
        val carets = EditorFactory.getInstance().getEditors(document, project).flatMap { editor ->
            editor.caretModel.allCarets.filter { !it.hasSelection() }.map { it to mapOffset(original, edits, it.offset) }
        }
        for (e in edits.asReversed()) document.replaceString(e.start, e.end, e.replacement)
        if (onlyWithin == null && document.text != formatted) {
            // The diff should reproduce the text exactly; if it ever doesn't, correctness beats caret preservation.
            document.setText(formatted)
            return
        }
        for ((caret, offset) in carets) caret.moveToOffset(offset.coerceIn(0, document.textLength))
    }

    /** Where the position [offset] of [original] ends up after [edits] (ascending, non-overlapping) are applied. */
    fun mapOffset(original: String, edits: List<Edit>, offset: Int): Int {
        var delta = 0
        for (e in edits) {
            when {
                e.end < offset || (e.end == offset && e.start < e.end) -> delta += e.replacement.length - (e.end - e.start)
                // An insertion right at the caret: stay in front of the following token, i.e. move past the insertion,
                // unless the caret sits in whitespace or at the end of the text.
                e.start == e.end && e.start == offset ->
                    if (offset < original.length && !original[offset].isWhitespace()) delta += e.replacement.length
                e.start < offset -> return e.start + delta + minOf(offset - e.start, e.replacement.length) // inside a replaced range
                else -> break
            }
        }
        return offset + delta
    }

    fun sameApartFromWhitespace(a: String, b: String): Boolean = a.filterNot { it.isWhitespace() } == b.filterNot { it.isWhitespace() }
}
