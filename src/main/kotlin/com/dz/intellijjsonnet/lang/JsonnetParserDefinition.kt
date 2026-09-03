package com.dz.intellijjsonnet.lang

import com.dz.intellijjsonnet.lang.lexer.JsonnetLexerAdapter
import com.dz.intellijjsonnet.lang.parser.JsonnetParser
import com.dz.intellijjsonnet.lang.psi.JsonnetFile
import com.dz.intellijjsonnet.lang.psi.JsonnetTypes
import com.dz.intellijjsonnet.lang.stubs.JsonnetFileElementType
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class JsonnetParserDefinition : ParserDefinition {

    companion object {
        val FILE = JsonnetFileElementType()
        val COMMENTS = TokenSet.create(JsonnetTypes.COMMENT)
        val STRINGS = TokenSet.create(JsonnetTypes.STRING)
        val WHITESPACE = TokenSet.WHITE_SPACE
    }

    override fun createLexer(project: Project?): Lexer = JsonnetLexerAdapter()

    override fun createParser(project: Project?): PsiParser = JsonnetParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = COMMENTS

    override fun getStringLiteralElements(): TokenSet = STRINGS

    override fun getWhitespaceTokens(): TokenSet = WHITESPACE

    override fun createElement(node: ASTNode): PsiElement = JsonnetTypes.Factory.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = JsonnetFile(viewProvider)
}
