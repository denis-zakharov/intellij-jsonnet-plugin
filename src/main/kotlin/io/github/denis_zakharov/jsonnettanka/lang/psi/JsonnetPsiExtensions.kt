package io.github.denis_zakharov.jsonnettanka.lang.psi

import com.intellij.psi.PsiElement

/**
 * Plain Kotlin extensions instead of Grammar-Kit's `methods=[...]` +
 * `psiImplUtilClass` mixin mechanism — that mechanism needs the util class
 * already *compiled* at generation time to detect method signatures, which
 * is a chicken-and-egg problem in a single Gradle build (Kotlin compiles
 * before Java, and the generated PSI types don't exist before `generateParser`
 * runs). Plain extensions over the generated interfaces sidestep it entirely.
 */

// JsonnetBind/JsonnetParam/JsonnetForSpec/JsonnetField also get a *real*
// getNameIdentifier() via their PsiNameIdentifierOwner mixins (see
// lang/psi/impl/), used by the platform's rename machinery, which dispatches
// on the interface type at runtime. But code elsewhere in this codebase
// refers to them by their plain generated interface type (JsonnetBind etc.),
// which doesn't declare that method — these extensions keep `.nameIdentifier`
// resolvable at those call sites too. Both coexist without conflict: which
// one applies depends on the static type of the expression, not a clash.

private fun identifierChild(element: PsiElement): PsiElement? =
    element.node.findChildByType(JsonnetTypes.IDENTIFIER)?.psi

val JsonnetDotSuffix.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetNameRef.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetBind.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetParam.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetForSpec.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetField.nameIdentifier: PsiElement? get() = identifierChild(this)
