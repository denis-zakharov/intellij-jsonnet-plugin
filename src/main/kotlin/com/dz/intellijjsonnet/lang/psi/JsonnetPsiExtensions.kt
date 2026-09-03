package com.dz.intellijjsonnet.lang.psi

import com.intellij.psi.PsiElement

/**
 * Plain Kotlin extensions instead of Grammar-Kit's `methods=[...]` +
 * `psiImplUtilClass` mixin mechanism — that mechanism needs the util class
 * already *compiled* at generation time to detect method signatures, which
 * is a chicken-and-egg problem in a single Gradle build (Kotlin compiles
 * before Java, and the generated PSI types don't exist before `generateParser`
 * runs). Plain extensions over the generated interfaces sidestep it entirely.
 */

private fun identifierChild(element: PsiElement): PsiElement? =
    element.node.findChildByType(JsonnetTypes.IDENTIFIER)?.psi

val JsonnetDotSuffix.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetNameRef.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetBind.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetParam.nameIdentifier: PsiElement? get() = identifierChild(this)
val JsonnetForSpec.nameIdentifier: PsiElement? get() = identifierChild(this)
