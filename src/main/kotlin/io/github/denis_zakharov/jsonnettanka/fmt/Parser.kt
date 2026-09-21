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

Ported to Kotlin from go-jsonnet v0.22.0 (internal/parser/parser.go, commit 567b61a); modified:
no locations or context propagation, errors are thrown as [ParseError].
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/** A parsed file: the root expression plus the fodder after its last token. */
class ParsedFile(val root: Node, val finalFodder: Fodder)

/** Parses [source] into a fodder-preserving AST, or throws [ParseError]. */
fun parseJsonnet(source: String): ParsedFile = Parser(Lexer(source).lex()).parseFile()

internal class Parser(private val t: List<Token>) {
    private var currT = 0

    private fun pop(): Token = t[currT++]
    private fun peek(): Token = t[currT]
    private fun doublePeek(): Token = t[currT + 1]

    private fun errorAt(msg: String, tok: Token) = ParseError(msg, tok.line, tok.column)

    private fun unexpected(tok: Token, whileParsing: String) =
        errorAt("Unexpected: $tok (while $whileParsing)", tok)

    private fun popExpect(kind: TokenKind): Token {
        val tok = pop()
        if (tok.kind != kind) throw errorAt("Expected token ${kind.text} but got $tok", tok)
        return tok
    }

    private fun popExpectOp(op: String): Token {
        val tok = pop()
        if (tok.kind != TokenKind.OPERATOR || tok.data != op) throw errorAt("Expected operator $op but got $tok", tok)
        return tok
    }

    private class Argument(val idFodder: Fodder?, val id: String?, val eqFodder: Fodder?, val expr: Node)

    /** Parses either `<f1> id <f2> = expr` or just `expr`. */
    private fun parseArgument(): Argument {
        var idFodder: Fodder? = null
        var id: String? = null
        var eqFodder: Fodder? = null
        if (peek().kind == TokenKind.IDENTIFIER && doublePeek().kind == TokenKind.OPERATOR && doublePeek().data == "=") {
            val ident = pop()
            id = ident.data
            idFodder = ident.fodder
            eqFodder = pop().fodder
        }
        return Argument(idFodder, id, eqFodder, parse(MAX_PRECEDENCE))
    }

    private class ParsedArguments(val end: Token, val args: Arguments, val gotComma: Boolean)

    private fun parseArguments(elementKind: String): ParsedArguments {
        val args = Arguments()
        var gotComma = false
        var namedArgumentAdded = false
        var first = true
        while (true) {
            var commaFodder = Fodder()
            val next = peek()

            if (next.kind == TokenKind.PAREN_R) {
                // gotComma can be true or false here.
                return ParsedArguments(pop(), args, gotComma)
            }

            if (!first && !gotComma) throw errorAt("Expected a comma before next $elementKind, got $next", next)

            val arg = parseArgument()

            if (peek().kind == TokenKind.COMMA) {
                commaFodder = pop().fodder
                gotComma = true
            } else {
                gotComma = false
            }

            if (arg.id == null) {
                if (namedArgumentAdded) throw errorAt("Positional argument after a named argument is not allowed", next)
                args.positional.add(CommaSeparatedExpr(arg.expr, if (gotComma) commaFodder else Fodder()))
            } else {
                namedArgumentAdded = true
                args.named.add(NamedArgument(arg.idFodder!!, arg.id, arg.eqFodder!!, arg.expr, commaFodder))
            }
            first = false
        }
    }

    /** Parses either `<f1> id <f2> = expr` or just `<f1> id`. */
    private fun parseParameter(): Parameter {
        val ident = popExpect(TokenKind.IDENTIFIER)
        val ret = Parameter(ident.fodder, ident.data)
        if (peek().kind == TokenKind.OPERATOR && peek().data == "=") {
            ret.eqFodder = pop().fodder
            ret.defaultArg = parse(MAX_PRECEDENCE)
        }
        return ret
    }

    private class ParsedParameters(val parenR: Token, val params: MutableList<Parameter>, val gotComma: Boolean)

    private fun parseParameters(elementKind: String): ParsedParameters {
        val params = mutableListOf<Parameter>()
        var gotComma = false
        var first = true
        while (true) {
            val next = peek()

            if (next.kind == TokenKind.PAREN_R) {
                // gotComma can be true or false here.
                return ParsedParameters(pop(), params, gotComma)
            }

            if (!first && !gotComma) throw errorAt("Expected a comma before next $elementKind, got $next", next)

            val param = parseParameter()

            if (peek().kind == TokenKind.COMMA) {
                param.commaFodder = pop().fodder
                gotComma = true
            } else {
                gotComma = false
            }
            params.add(param)
            first = false
        }
    }

    private fun parseBind(binds: MutableList<LocalBind>): Token {
        val varId = popExpect(TokenKind.IDENTIFIER)
        for (b in binds) {
            if (b.variable == varId.data) throw errorAt("Duplicate local var: ${varId.data}", varId)
        }

        var fn: FunctionExpr? = null
        if (peek().kind == TokenKind.PAREN_L) {
            val parenL = pop()
            val p = parseParameters("function parameter")
            fn = FunctionExpr(parenL.fodder, p.params, p.gotComma, p.parenR.fodder, LiteralNull(Fodder()))
        }

        val eq = popExpectOp("=")
        val body = parse(MAX_PRECEDENCE)

        val delim = pop()
        if (delim.kind != TokenKind.SEMICOLON && delim.kind != TokenKind.COMMA) {
            throw errorAt("Expected , or ; but got $delim", delim)
        }

        fn?.body = body
        binds.add(LocalBind(varId.fodder, varId.data, eq.fodder, body, delim.fodder, fn))
        return delim
    }

    private class ObjectAssignmentOp(val opFodder: Fodder, val plusSugar: Boolean, val hide: ObjectFieldHide)

    private fun parseObjectAssignmentOp(): ObjectAssignmentOp {
        val op = popExpect(TokenKind.OPERATOR)
        var opStr = op.data
        var plusSugar = false
        if (opStr[0] == '+') {
            plusSugar = true
            opStr = opStr.substring(1)
        }
        var numColons = 0
        while (opStr.isNotEmpty()) {
            if (opStr[0] != ':') throw errorAt("Expected one of :, ::, :::, +:, +::, +:::, got: ${op.data}", op)
            opStr = opStr.substring(1)
            numColons++
        }
        val hide = when (numColons) {
            1 -> ObjectFieldHide.INHERIT
            2 -> ObjectFieldHide.HIDDEN
            3 -> ObjectFieldHide.VISIBLE
            else -> throw errorAt("Expected one of :, ::, :::, +:, +::, +:::, got: ${op.data}", op)
        }
        return ObjectAssignmentOp(op.fodder, plusSugar, hide)
    }

    private class ObjectRemainder(val node: Node, val last: Token)

    private fun parseObjectRemainderComp(
        fields: MutableList<ObjectField>,
        gotComma: Boolean,
        tok: Token,
        next: Token,
    ): ObjectRemainder {
        var numFields = 0
        var numAsserts = 0
        var field: ObjectField? = null
        for (f in fields) {
            if (f.kind == ObjectFieldKind.LOCAL) continue
            if (f.kind == ObjectFieldKind.ASSERT) {
                numAsserts++
                continue
            }
            numFields++
            field = f
        }
        if (numAsserts > 0) throw errorAt("Object comprehension cannot have asserts", next)
        if (numFields != 1) throw errorAt("Object comprehension can only have one field", next)
        if (field!!.hide != ObjectFieldHide.INHERIT) throw errorAt("Object comprehensions cannot have hidden fields", next)
        if (field.kind != ObjectFieldKind.EXPR) throw errorAt("Object comprehensions can only have [e] fields", next)
        val (spec, last) = parseComprehensionSpecs(next, TokenKind.BRACE_R)
        return ObjectRemainder(
            ObjectComp(fields, gotComma, Fodder(), spec, last.fodder, tok.fodder),
            last,
        )
    }

    private fun parseObjectRemainderField(literalFields: MutableSet<String>, next: Token): ObjectField {
        val kind: ObjectFieldKind
        var fodder1 = Fodder()
        var expr1: Node? = null
        var id: String? = null
        var fodder2 = Fodder()

        when (next.kind) {
            TokenKind.IDENTIFIER -> {
                kind = ObjectFieldKind.ID
                id = next.data
                fodder1 = next.fodder
            }
            TokenKind.STRING_DOUBLE, TokenKind.STRING_SINGLE, TokenKind.STRING_BLOCK,
            TokenKind.VERBATIM_STRING_DOUBLE, TokenKind.VERBATIM_STRING_SINGLE,
            -> {
                kind = ObjectFieldKind.STR
                expr1 = tokenStringToAst(next)
            }
            else -> {
                fodder1 = next.fodder
                kind = ObjectFieldKind.EXPR
                expr1 = parse(MAX_PRECEDENCE)
                fodder2 = popExpect(TokenKind.BRACKET_R).fodder
            }
        }

        var parenL: Token? = null
        var parenR: Token? = null
        var params: MutableList<Parameter> = mutableListOf()
        var methComma = false
        var isMethod = false
        if (peek().kind == TokenKind.PAREN_L) {
            parenL = pop()
            val p = parseParameters("method parameter")
            parenR = p.parenR
            params = p.params
            methComma = p.gotComma
            isMethod = true
        }

        val op = parseObjectAssignmentOp()

        if (op.plusSugar && isMethod) throw errorAt("Cannot use +: syntax sugar in a method: ${next.data}", next)

        if (kind != ObjectFieldKind.EXPR) {
            if (!literalFields.add(next.data)) throw errorAt("Duplicate field: ${next.data}", next)
        }

        val body = parse(MAX_PRECEDENCE)

        val method = if (isMethod) FunctionExpr(parenL!!.fodder, params, methComma, parenR!!.fodder, body) else null

        val commaFodder = if (peek().kind == TokenKind.COMMA) peek().fodder.copy() else Fodder()

        return ObjectField(
            kind = kind,
            hide = op.hide,
            superSugar = op.plusSugar,
            method = method,
            fodder1 = fodder1,
            expr1 = expr1,
            id = id,
            fodder2 = fodder2,
            opFodder = op.opFodder,
            expr2 = body,
            expr3 = null,
            commaFodder = commaFodder,
        )
    }

    private fun parseObjectRemainderLocal(binds: MutableSet<String>, next: Token): ObjectField {
        val varId = popExpect(TokenKind.IDENTIFIER)
        val id = varId.data
        if (id in binds) throw errorAt("Duplicate local var: $id", varId)

        var parenL: Token? = null
        var parenR: Token? = null
        var params: MutableList<Parameter> = mutableListOf()
        var funcComma = false
        var isMethod = false
        if (peek().kind == TokenKind.PAREN_L) {
            parenL = pop()
            isMethod = true
            val p = parseParameters("function parameter")
            parenR = p.parenR
            params = p.params
            funcComma = p.gotComma
        }
        val opToken = popExpectOp("=")

        val body = parse(MAX_PRECEDENCE)

        val method = if (isMethod) FunctionExpr(parenL!!.fodder, params, funcComma, parenR!!.fodder, body) else null

        binds.add(id)

        val commaFodder = if (peek().kind == TokenKind.COMMA) peek().fodder.copy() else Fodder()

        return ObjectField(
            kind = ObjectFieldKind.LOCAL,
            hide = ObjectFieldHide.VISIBLE,
            superSugar = false,
            method = method,
            fodder1 = next.fodder,
            expr1 = null,
            id = id,
            fodder2 = varId.fodder,
            opFodder = opToken.fodder,
            expr2 = body,
            expr3 = null,
            commaFodder = commaFodder,
        )
    }

    private fun parseObjectRemainderAssert(next: Token): ObjectField {
        val cond = parse(MAX_PRECEDENCE)
        var msg: Node? = null
        var colonFodder = Fodder()
        if (peek().kind == TokenKind.OPERATOR && peek().data == ":") {
            colonFodder = pop().fodder
            msg = parse(MAX_PRECEDENCE)
        }
        val commaFodder = if (peek().kind == TokenKind.COMMA) peek().fodder.copy() else Fodder()

        return ObjectField(
            kind = ObjectFieldKind.ASSERT,
            hide = ObjectFieldHide.VISIBLE,
            superSugar = false,
            method = null,
            fodder1 = next.fodder,
            expr1 = null,
            id = null,
            fodder2 = Fodder(),
            opFodder = colonFodder,
            expr2 = cond,
            expr3 = msg,
            commaFodder = commaFodder,
        )
    }

    /** Parses an object or object comprehension without its leading brace. */
    private fun parseObjectRemainder(tok: Token): ObjectRemainder {
        val fields = mutableListOf<ObjectField>()
        val literalFields = mutableSetOf<String>()
        val binds = mutableSetOf<String>()

        var gotComma = false
        var first = true

        var next = pop()

        while (true) {
            if (next.kind == TokenKind.BRACE_R) {
                return ObjectRemainder(ObjectLit(fields, gotComma, next.fodder, tok.fodder), next)
            }

            if (next.kind == TokenKind.FOR) {
                // It's a comprehension
                return parseObjectRemainderComp(fields, gotComma, tok, next)
            }

            if (!gotComma && !first) throw errorAt("Expected a comma before next field", next)

            val field = when (next.kind) {
                TokenKind.BRACKET_L, TokenKind.IDENTIFIER, TokenKind.STRING_DOUBLE, TokenKind.STRING_SINGLE,
                TokenKind.STRING_BLOCK, TokenKind.VERBATIM_STRING_DOUBLE, TokenKind.VERBATIM_STRING_SINGLE,
                -> parseObjectRemainderField(literalFields, next)
                TokenKind.LOCAL -> parseObjectRemainderLocal(binds, next)
                TokenKind.ASSERT -> parseObjectRemainderAssert(next)
                else -> throw unexpected(next, "parsing field definition")
            }
            fields.add(field)

            next = pop()
            if (next.kind == TokenKind.COMMA) {
                gotComma = true
                next = pop()
            } else {
                gotComma = false
            }
            first = false
        }
    }

    /** Parses `for x in expr for y in expr if expr for z in expr ...` up to and including the [end] token. */
    private fun parseComprehensionSpecs(forTokenIn: Token, end: TokenKind): Pair<ForSpec, Token> {
        var forToken = forTokenIn
        var outer: ForSpec? = null
        while (true) {
            val varId = popExpect(TokenKind.IDENTIFIER)
            val inToken = popExpect(TokenKind.IN)
            val arr = parse(MAX_PRECEDENCE)
            val forSpec = ForSpec(forToken.fodder, varId.fodder, varId.data, inToken.fodder, arr, outer = outer)

            var maybeIf = pop()
            while (maybeIf.kind == TokenKind.IF) {
                val cond = parse(MAX_PRECEDENCE)
                forSpec.conditions.add(IfSpec(cond, maybeIf.fodder))
                maybeIf = pop()
            }
            if (maybeIf.kind == end) return forSpec to maybeIf
            if (maybeIf.kind != TokenKind.FOR) {
                throw errorAt("Expected for, if or ${end.text} after for clause, got: $maybeIf", maybeIf)
            }
            forToken = maybeIf
            outer = forSpec
        }
    }

    /** Assumes the leading `[` has been consumed and passed as [tok]. Consumes the trailing `]`. */
    private fun parseArray(tok: Token): Node {
        if (peek().kind == TokenKind.BRACKET_R) {
            val bracketR = pop()
            return ArrayLit(mutableListOf(), false, bracketR.fodder, tok.fodder)
        }

        val first = parse(MAX_PRECEDENCE)
        var gotComma = false
        var commaFodder = Fodder()
        if (peek().kind == TokenKind.COMMA) {
            commaFodder = pop().fodder
            gotComma = true
        }

        if (peek().kind == TokenKind.FOR) {
            // It's a comprehension
            val forToken = pop()
            val (spec, last) = parseComprehensionSpecs(forToken, TokenKind.BRACKET_R)
            return ArrayComp(first, commaFodder, gotComma, spec, last.fodder, tok.fodder)
        }

        // Not a comprehension: it can have more elements.
        val elements = mutableListOf(CommaSeparatedExpr(first, commaFodder))
        val bracketR: Token
        while (true) {
            val next = peek()
            if (next.kind == TokenKind.BRACKET_R) {
                bracketR = pop()
                break
            }
            if (!gotComma) throw errorAt("Expected a comma before next array element", next)
            val nextElem = CommaSeparatedExpr(parse(MAX_PRECEDENCE))
            if (peek().kind == TokenKind.COMMA) {
                nextElem.commaFodder = pop().fodder
                gotComma = true
            } else {
                gotComma = false
            }
            elements.add(nextElem)
        }
        return ArrayLit(elements, gotComma, bracketR.fodder, tok.fodder)
    }

    private fun tokenStringToAst(tok: Token): LiteralString {
        val node: LiteralString
        var validate = true
        when (tok.kind) {
            TokenKind.STRING_SINGLE -> node = LiteralString(tok.data, LiteralStringKind.SINGLE, fodder = tok.fodder)
            TokenKind.STRING_DOUBLE -> node = LiteralString(tok.data, LiteralStringKind.DOUBLE, fodder = tok.fodder)
            TokenKind.STRING_BLOCK -> {
                node = LiteralString(
                    tok.data, LiteralStringKind.BLOCK, tok.stringBlockIndent, tok.stringBlockTermIndent, tok.fodder,
                )
                validate = false
            }
            TokenKind.VERBATIM_STRING_DOUBLE -> {
                node = LiteralString(tok.data, LiteralStringKind.VERBATIM_DOUBLE, fodder = tok.fodder)
                validate = false
            }
            TokenKind.VERBATIM_STRING_SINGLE -> {
                node = LiteralString(tok.data, LiteralStringKind.VERBATIM_SINGLE, fodder = tok.fodder)
                validate = false
            }
            else -> throw IllegalStateException("Not a string token $tok")
        }
        if (validate) {
            try {
                unescapeJsonnetString(node.value)
            } catch (e: IllegalArgumentException) {
                throw errorAt(e.message ?: "Bad string literal", tok)
            }
        }
        return node
    }

    private fun parseTerminal(): Node {
        val tok = pop()
        when (tok.kind) {
            TokenKind.ASSERT, TokenKind.BRACE_R, TokenKind.BRACKET_R, TokenKind.COMMA, TokenKind.DOT, TokenKind.ELSE,
            TokenKind.ERROR, TokenKind.FOR, TokenKind.FUNCTION, TokenKind.IF, TokenKind.IN, TokenKind.IMPORT,
            TokenKind.IMPORT_STR, TokenKind.IMPORT_BIN, TokenKind.LOCAL, TokenKind.OPERATOR, TokenKind.PAREN_R,
            TokenKind.SEMICOLON, TokenKind.TAIL_STRICT, TokenKind.THEN,
            -> throw unexpected(tok, "parsing terminal")

            TokenKind.END_OF_FILE -> throw errorAt("Unexpected end of file", tok)

            TokenKind.BRACE_L -> return parseObjectRemainder(tok).node
            TokenKind.BRACKET_L -> return parseArray(tok)

            TokenKind.PAREN_L -> {
                val inner = parse(MAX_PRECEDENCE)
                val tokRight = popExpect(TokenKind.PAREN_R)
                return Parens(inner, tokRight.fodder, tok.fodder)
            }

            TokenKind.NUMBER -> return LiteralNumber(tok.data, tok.fodder)
            TokenKind.STRING_DOUBLE, TokenKind.STRING_SINGLE, TokenKind.STRING_BLOCK,
            TokenKind.VERBATIM_STRING_DOUBLE, TokenKind.VERBATIM_STRING_SINGLE,
            -> return tokenStringToAst(tok)
            TokenKind.FALSE -> return LiteralBoolean(false, tok.fodder)
            TokenKind.TRUE -> return LiteralBoolean(true, tok.fodder)
            TokenKind.NULL_LIT -> return LiteralNull(tok.fodder)

            TokenKind.DOLLAR -> return Dollar(tok.fodder)
            TokenKind.IDENTIFIER -> return Var(tok.data, tok.fodder)
            TokenKind.SELF -> return SelfExpr(tok.fodder)
            TokenKind.SUPER -> {
                val next = pop()
                var index: Node? = null
                var id: String? = null
                val idFodder: Fodder
                when (next.kind) {
                    TokenKind.DOT -> {
                        val fieldId = popExpect(TokenKind.IDENTIFIER)
                        idFodder = fieldId.fodder
                        id = fieldId.data
                    }
                    TokenKind.BRACKET_L -> {
                        index = parse(MAX_PRECEDENCE)
                        idFodder = popExpect(TokenKind.BRACKET_R).fodder
                    }
                    else -> throw errorAt("Expected . or [ after super", tok)
                }
                return SuperIndex(next.fodder, index, idFodder, id, tok.fodder)
            }
        }
    }

    private fun parseImportBody(): LiteralString {
        val bodyTok = peek()
        val body = parse(MAX_PRECEDENCE)
        if (body is LiteralString) {
            if (body.kind == LiteralStringKind.BLOCK) throw errorAt("Block string literals not allowed in imports", bodyTok)
            return body
        }
        throw errorAt("Computed imports are not allowed", bodyTok)
    }

    private fun parse(prec: Int): Node {
        val begin = peek()

        when (begin.kind) {
            // These cases have effectively MAX_PRECEDENCE as the first call to parse will parse them.
            TokenKind.ASSERT -> {
                pop()
                val cond = parse(MAX_PRECEDENCE)
                var msg: Node? = null
                var colonFodder = Fodder()
                if (peek().kind == TokenKind.OPERATOR && peek().data == ":") {
                    colonFodder = pop().fodder
                    msg = parse(MAX_PRECEDENCE)
                }
                val semicolon = popExpect(TokenKind.SEMICOLON)
                val rest = parse(MAX_PRECEDENCE)
                return Assert(cond, colonFodder, msg, semicolon.fodder, rest, begin.fodder)
            }

            TokenKind.ERROR -> {
                pop()
                return ErrorExpr(parse(MAX_PRECEDENCE), begin.fodder)
            }

            TokenKind.IF -> {
                pop()
                val cond = parse(MAX_PRECEDENCE)
                val thenToken = popExpect(TokenKind.THEN)
                val branchTrue = parse(MAX_PRECEDENCE)
                var branchFalse: Node? = null
                var elseFodder = Fodder()
                if (peek().kind == TokenKind.ELSE) {
                    elseFodder = pop().fodder
                    branchFalse = parse(MAX_PRECEDENCE)
                }
                return Conditional(cond, thenToken.fodder, branchTrue, elseFodder, branchFalse, begin.fodder)
            }

            TokenKind.FUNCTION -> {
                pop()
                val next = pop()
                if (next.kind != TokenKind.PAREN_L) throw errorAt("Expected ( but got $next", next)
                val p = parseParameters("function parameter")
                val body = parse(MAX_PRECEDENCE)
                return FunctionExpr(next.fodder, p.params, p.gotComma, p.parenR.fodder, body, begin.fodder)
            }

            TokenKind.IMPORT -> {
                pop()
                return Import(parseImportBody(), begin.fodder)
            }
            TokenKind.IMPORT_STR -> {
                pop()
                return ImportStr(parseImportBody(), begin.fodder)
            }
            TokenKind.IMPORT_BIN -> {
                pop()
                return ImportBin(parseImportBody(), begin.fodder)
            }

            TokenKind.LOCAL -> {
                pop()
                val binds = mutableListOf<LocalBind>()
                while (true) {
                    val delim = parseBind(binds)
                    if (delim.kind == TokenKind.SEMICOLON) break
                }
                val body = parse(MAX_PRECEDENCE)
                return Local(binds, body, begin.fodder)
            }

            else -> {}
        }

        // Unary operator
        if (begin.kind == TokenKind.OPERATOR) {
            val uop = UnaryOp.byText[begin.data] ?: throw errorAt("Not a unary operator: ${begin.data}", begin)
            if (prec == UNARY_PRECEDENCE) {
                pop()
                val expr = parse(prec)
                return Unary(uop, expr, begin.fodder)
            }
        }

        // Base case
        if (prec == 0) return parseTerminal()

        var lhs = parse(prec - 1)

        while (true) {
            // The next token must be a binary operator. Check the precedence is right for this level; if we're
            // parsing operators with higher precedence, return lhs and let lower levels deal with the operator.
            var bop: BinaryOp? = null
            val nextTok = peek()
            when (nextTok.kind) {
                TokenKind.IN -> {
                    bop = BinaryOp.IN
                    if (bop.precedence != prec) return lhs
                }
                TokenKind.OPERATOR -> {
                    // ':' terminates an assert's condition and '::' terminates `[e::]`; neither is a binary op.
                    if (nextTok.data == ":") return lhs
                    if (nextTok.data == "::") return lhs
                    bop = BinaryOp.byText[nextTok.data] ?: throw errorAt("Not a binary operator: ${nextTok.data}", nextTok)
                    if (bop.precedence != prec) return lhs
                }
                TokenKind.DOT, TokenKind.BRACKET_L, TokenKind.PAREN_L, TokenKind.BRACE_L -> {
                    if (APPLY_PRECEDENCE != prec) return lhs
                }
                else -> return lhs
            }

            val op = pop()
            when (op.kind) {
                TokenKind.BRACKET_L -> lhs = parseIndexOrSlice(lhs, op)
                TokenKind.DOT -> {
                    val fieldId = popExpect(TokenKind.IDENTIFIER)
                    lhs = Index(lhs, op.fodder, null, fieldId.fodder, fieldId.data)
                }
                TokenKind.PAREN_L -> {
                    val parsed = parseArguments("function argument")
                    var tailStrict = false
                    var tailStrictFodder = Fodder()
                    if (peek().kind == TokenKind.TAIL_STRICT) {
                        tailStrictFodder = pop().fodder
                        tailStrict = true
                    }
                    lhs = Apply(lhs, op.fodder, parsed.args, parsed.gotComma, parsed.end.fodder, tailStrict, tailStrictFodder)
                }
                TokenKind.BRACE_L -> {
                    val obj = parseObjectRemainder(op)
                    lhs = ApplyBrace(lhs, obj.node)
                }
                else -> {
                    if (op.kind == TokenKind.IN && peek().kind == TokenKind.SUPER) {
                        val superTok = pop()
                        lhs = InSuper(lhs, op.fodder, superTok.fodder)
                    } else {
                        val rhs = parse(prec - 1)
                        lhs = Binary(lhs, op.fodder, bop!!, rhs)
                    }
                }
            }
        }
    }

    private fun parseIndexOrSlice(lhs: Node, op: Token): Node {
        val indexes = arrayOfNulls<Node>(3)
        val fodders = Array(3) { Fodder() }
        var colonsConsumed = 0

        var end: Token? = null
        var readyForNextIndex = true
        var rightBracketFodder = Fodder()
        while (colonsConsumed < 3) {
            // go-jsonnet compares only `data`, so a string literal ":" also counts as a colon; kept for parity.
            if (peek().kind == TokenKind.BRACKET_R) {
                end = pop()
                rightBracketFodder = end.fodder
                break
            } else if (peek().data == ":") {
                end = pop()
                fodders[colonsConsumed] = end.fodder
                colonsConsumed++
                readyForNextIndex = true
            } else if (peek().data == "::") {
                end = pop()
                fodders[colonsConsumed] = end.fodder
                colonsConsumed += 2
                readyForNextIndex = true
            } else if (readyForNextIndex) {
                indexes[colonsConsumed] = parse(MAX_PRECEDENCE)
                readyForNextIndex = false
            } else {
                throw errorAt("Expected token ${TokenKind.BRACKET_R.text} but got ${peek()}", peek())
            }
        }
        if (colonsConsumed > 2) {
            // example: target[42:42:42:42]
            throw errorAt("Invalid slice: too many colons", end!!)
        }
        if (colonsConsumed == 0 && readyForNextIndex) {
            // example: target[]
            throw errorAt("ast.Index requires an expression", end!!)
        }
        return if (colonsConsumed > 0) {
            Slice(lhs, op.fodder, indexes[0], fodders[0], indexes[1], fodders[1], indexes[2], rightBracketFodder)
        } else {
            Index(lhs, op.fodder, indexes[0], rightBracketFodder, null)
        }
    }

    fun parseFile(): ParsedFile {
        val expr = parse(MAX_PRECEDENCE)
        val eof = peek()
        if (eof.kind != TokenKind.END_OF_FILE) throw errorAt("Did not expect: $eof", eof)
        return ParsedFile(expr, eof.fodder)
    }
}
