package io.github.denis_zakharov.jsonnettanka.editor

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class JsonnetBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(JsonnetTypes.LBRACE, JsonnetTypes.RBRACE, true),
        BracePair(JsonnetTypes.LBRACK, JsonnetTypes.RBRACK, true),
        BracePair(JsonnetTypes.LPAREN, JsonnetTypes.RPAREN, false),
    )

    override fun getPairs(): Array<BracePair> = pairs

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}
