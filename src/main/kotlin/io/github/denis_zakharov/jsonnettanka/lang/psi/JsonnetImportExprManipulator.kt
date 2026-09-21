package io.github.denis_zakharov.jsonnettanka.lang.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.util.IncorrectOperationException

/**
 * Lets the platform rewrite the path inside an `import '...'` (file rename/move refactorings update
 * import paths through their [com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference]s).
 */
class JsonnetImportExprManipulator : AbstractElementManipulator<JsonnetImportExpr>() {

    override fun getRangeInElement(element: JsonnetImportExpr): TextRange {
        val literal = element.node.findChildByType(JsonnetTypes.STRING) ?: return super.getRangeInElement(element)
        val start = literal.psi.startOffsetInParent
        return TextRange(start + 1, start + literal.textLength - 1)
    }

    override fun handleContentChange(element: JsonnetImportExpr, range: TextRange, newContent: String): JsonnetImportExpr {
        val newText = range.replace(element.text, newContent)
        val replacement = JsonnetElementFactory.createImportExpr(element.project, newText)
            ?: throw IncorrectOperationException("Cannot build an import from: $newText")
        return element.replace(replacement) as JsonnetImportExpr
    }
}
