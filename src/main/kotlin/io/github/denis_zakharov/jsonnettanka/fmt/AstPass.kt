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

Ported to Kotlin from go-jsonnet v0.22.0 (internal/pass/pass.go, commit 567b61a); modified: `visit` returns the
(possibly replaced) node instead of taking a pointer to the parent's slot.
*/
package io.github.denis_zakharov.jsonnettanka.fmt

/**
 * Base class for AST passes: does the traversal so a pass only overrides what it cares about. The traversal
 * order matters — passes with state ([EnforceCommentStyle], [StripAllButComments]) depend on it — and mirrors
 * go-jsonnet exactly. [visit] returns the node to store in the parent's slot, which is how a pass replaces a node.
 */
open class AstPass {
    open fun baseContext(): Any? = null

    open fun fodderElement(element: FodderElement, ctx: Any?) {}

    open fun fodder(fodder: Fodder, ctx: Any?) {
        for (element in fodder.elements) fodderElement(element, ctx)
    }

    open fun forSpec(forSpec: ForSpec, ctx: Any?) {
        forSpec.outer?.let { forSpec(it, ctx) }
        fodder(forSpec.forFodder, ctx)
        fodder(forSpec.varFodder, ctx)
        fodder(forSpec.inFodder, ctx)
        forSpec.expr = visit(forSpec.expr, ctx)
        for (cond in forSpec.conditions) {
            fodder(cond.ifFodder, ctx)
            cond.expr = visit(cond.expr, ctx)
        }
    }

    open fun parameters(l: Fodder, params: MutableList<Parameter>, r: Fodder, ctx: Any?) {
        fodder(l, ctx)
        for (param in params) {
            fodder(param.nameFodder, ctx)
            param.defaultArg?.let {
                fodder(param.eqFodder, ctx)
                param.defaultArg = visit(it, ctx)
            }
            fodder(param.commaFodder, ctx)
        }
        fodder(r, ctx)
    }

    open fun arguments(l: Fodder, args: Arguments, r: Fodder, ctx: Any?) {
        fodder(l, ctx)
        for (arg in args.positional) {
            arg.expr = visit(arg.expr, ctx)
            fodder(arg.commaFodder, ctx)
        }
        for (arg in args.named) {
            fodder(arg.nameFodder, ctx)
            fodder(arg.eqFodder, ctx)
            arg.arg = visit(arg.arg, ctx)
            fodder(arg.commaFodder, ctx)
        }
        fodder(r, ctx)
    }

    open fun fieldParams(field: ObjectField, ctx: Any?) {
        field.method?.let { parameters(it.parenLeftFodder, it.parameters, it.parenRightFodder, ctx) }
    }

    open fun objectField(field: ObjectField, ctx: Any?) {
        when (field.kind) {
            ObjectFieldKind.LOCAL -> {
                fodder(field.fodder1, ctx)
                fodder(field.fodder2, ctx)
                fieldParams(field, ctx)
                fodder(field.opFodder, ctx)
                field.expr2 = visit(field.expr2!!, ctx)
            }
            ObjectFieldKind.ID -> {
                fodder(field.fodder1, ctx)
                fieldParams(field, ctx)
                fodder(field.opFodder, ctx)
                field.expr2 = visit(field.expr2!!, ctx)
            }
            ObjectFieldKind.STR -> {
                field.expr1 = visit(field.expr1!!, ctx)
                fieldParams(field, ctx)
                fodder(field.opFodder, ctx)
                field.expr2 = visit(field.expr2!!, ctx)
            }
            ObjectFieldKind.EXPR -> {
                fodder(field.fodder1, ctx)
                field.expr1 = visit(field.expr1!!, ctx)
                fodder(field.fodder2, ctx)
                fieldParams(field, ctx)
                fodder(field.opFodder, ctx)
                field.expr2 = visit(field.expr2!!, ctx)
            }
            ObjectFieldKind.ASSERT -> {
                fodder(field.fodder1, ctx)
                field.expr2 = visit(field.expr2!!, ctx)
                field.expr3?.let {
                    fodder(field.opFodder, ctx)
                    field.expr3 = visit(it, ctx)
                }
            }
        }
        fodder(field.commaFodder, ctx)
    }

    open fun objectFields(fields: MutableList<ObjectField>, ctx: Any?) {
        for (field in fields) objectField(field, ctx)
    }

    open fun onApply(node: Apply, ctx: Any?) {
        node.target = visit(node.target, ctx)
        arguments(node.fodderLeft, node.arguments, node.fodderRight, ctx)
        if (node.tailStrict) fodder(node.tailStrictFodder, ctx)
    }

    open fun onApplyBrace(node: ApplyBrace, ctx: Any?) {
        node.left = visit(node.left, ctx)
        node.right = visit(node.right, ctx)
    }

    open fun onArray(node: ArrayLit, ctx: Any?) {
        for (element in node.elements) {
            element.expr = visit(element.expr, ctx)
            fodder(element.commaFodder, ctx)
        }
        fodder(node.closeFodder, ctx)
    }

    open fun onArrayComp(node: ArrayComp, ctx: Any?) {
        node.body = visit(node.body, ctx)
        fodder(node.trailingCommaFodder, ctx)
        forSpec(node.spec, ctx)
        fodder(node.closeFodder, ctx)
    }

    open fun onAssert(node: Assert, ctx: Any?) {
        node.cond = visit(node.cond, ctx)
        node.message?.let {
            fodder(node.colonFodder, ctx)
            node.message = visit(it, ctx)
        }
        fodder(node.semicolonFodder, ctx)
        node.rest = visit(node.rest, ctx)
    }

    open fun onBinary(node: Binary, ctx: Any?) {
        node.left = visit(node.left, ctx)
        fodder(node.opFodder, ctx)
        node.right = visit(node.right, ctx)
    }

    open fun onConditional(node: Conditional, ctx: Any?) {
        node.cond = visit(node.cond, ctx)
        fodder(node.thenFodder, ctx)
        node.branchTrue = visit(node.branchTrue, ctx)
        node.branchFalse?.let {
            fodder(node.elseFodder, ctx)
            node.branchFalse = visit(it, ctx)
        }
    }

    open fun onDollar(node: Dollar, ctx: Any?) {}

    open fun onError(node: ErrorExpr, ctx: Any?) {
        node.expr = visit(node.expr, ctx)
    }

    open fun onFunction(node: FunctionExpr, ctx: Any?) {
        parameters(node.parenLeftFodder, node.parameters, node.parenRightFodder, ctx)
        node.body = visit(node.body, ctx)
    }

    open fun onImport(node: ImportNode, ctx: Any?) {
        fodder(node.file.fodder, ctx)
        onLiteralString(node.file, ctx)
    }

    open fun onIndex(node: Index, ctx: Any?) {
        node.target = visit(node.target, ctx)
        fodder(node.leftBracketFodder, ctx)
        if (node.id == null) {
            node.index = visit(node.index!!, ctx)
            fodder(node.rightBracketFodder, ctx)
        }
    }

    open fun onInSuper(node: InSuper, ctx: Any?) {
        node.index = visit(node.index, ctx)
    }

    open fun onLiteralBoolean(node: LiteralBoolean, ctx: Any?) {}
    open fun onLiteralNull(node: LiteralNull, ctx: Any?) {}
    open fun onLiteralNumber(node: LiteralNumber, ctx: Any?) {}
    open fun onLiteralString(node: LiteralString, ctx: Any?) {}

    open fun onLocal(node: Local, ctx: Any?) {
        for (bind in node.binds) {
            fodder(bind.varFodder, ctx)
            bind.fn?.let { parameters(it.parenLeftFodder, it.parameters, it.parenRightFodder, ctx) }
            fodder(bind.eqFodder, ctx)
            bind.body = visit(bind.body, ctx)
            fodder(bind.closeFodder, ctx)
        }
        node.body = visit(node.body, ctx)
    }

    open fun onObject(node: ObjectLit, ctx: Any?) {
        objectFields(node.fields, ctx)
        fodder(node.closeFodder, ctx)
    }

    open fun onObjectComp(node: ObjectComp, ctx: Any?) {
        objectFields(node.fields, ctx)
        forSpec(node.spec, ctx)
        fodder(node.closeFodder, ctx)
    }

    open fun onParens(node: Parens, ctx: Any?) {
        node.inner = visit(node.inner, ctx)
        fodder(node.closeFodder, ctx)
    }

    open fun onSelf(node: SelfExpr, ctx: Any?) {}

    open fun onSlice(node: Slice, ctx: Any?) {
        node.target = visit(node.target, ctx)
        fodder(node.leftBracketFodder, ctx)
        node.beginIndex?.let { node.beginIndex = visit(it, ctx) }
        fodder(node.endColonFodder, ctx)
        node.endIndex?.let { node.endIndex = visit(it, ctx) }
        fodder(node.stepColonFodder, ctx)
        node.step?.let { node.step = visit(it, ctx) }
        fodder(node.rightBracketFodder, ctx)
    }

    open fun onSuperIndex(node: SuperIndex, ctx: Any?) {
        fodder(node.dotFodder, ctx)
        if (node.id == null) node.index = visit(node.index!!, ctx)
        fodder(node.idFodder, ctx)
    }

    open fun onUnary(node: Unary, ctx: Any?) {
        node.expr = visit(node.expr, ctx)
    }

    open fun onVar(node: Var, ctx: Any?) {}

    /** Traverses into an arbitrary node; returns the node that should take its place (usually [node]). */
    open fun visit(node: Node, ctx: Any?): Node {
        fodder(node.fodder, ctx)
        when (node) {
            is Apply -> onApply(node, ctx)
            is ApplyBrace -> onApplyBrace(node, ctx)
            is ArrayLit -> onArray(node, ctx)
            is ArrayComp -> onArrayComp(node, ctx)
            is Assert -> onAssert(node, ctx)
            is Binary -> onBinary(node, ctx)
            is Conditional -> onConditional(node, ctx)
            is Dollar -> onDollar(node, ctx)
            is ErrorExpr -> onError(node, ctx)
            is FunctionExpr -> onFunction(node, ctx)
            is ImportNode -> onImport(node, ctx)
            is Index -> onIndex(node, ctx)
            is InSuper -> onInSuper(node, ctx)
            is LiteralBoolean -> onLiteralBoolean(node, ctx)
            is LiteralNull -> onLiteralNull(node, ctx)
            is LiteralNumber -> onLiteralNumber(node, ctx)
            is LiteralString -> onLiteralString(node, ctx)
            is Local -> onLocal(node, ctx)
            is ObjectLit -> onObject(node, ctx)
            is ObjectComp -> onObjectComp(node, ctx)
            is Parens -> onParens(node, ctx)
            is SelfExpr -> onSelf(node, ctx)
            is Slice -> onSlice(node, ctx)
            is SuperIndex -> onSuperIndex(node, ctx)
            is Unary -> onUnary(node, ctx)
            is Var -> onVar(node, ctx)
        }
        return node
    }

    /** Processes a whole file including the fodder after its last token; returns the (possibly replaced) root. */
    open fun file(node: Node, finalFodder: Fodder): Node {
        val ctx = baseContext()
        val result = visit(node, ctx)
        fodder(finalFodder, ctx)
        return result
    }
}
