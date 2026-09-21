package io.github.denis_zakharov.jsonnettanka.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetDotSuffix
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetNameRef
import io.github.denis_zakharov.jsonnettanka.lang.psi.nameIdentifier
import io.github.denis_zakharov.jsonnettanka.lang.psi.reference.JsonnetResolver
import io.github.denis_zakharov.jsonnettanka.lang.stubs.JsonnetStubIndexUtil
import io.github.denis_zakharov.jsonnettanka.stdlib.SjsonnetOnlyStd
import io.github.denis_zakharov.jsonnettanka.stdlib.StdLibRegistry

/**
 * Flags `std.<member>` where the member exists in sjsonnet but not in go-jsonnet ([SjsonnetOnlyStd]). The preview
 * evaluates such code happily, so without this the failure only shows up later in `tk show`.
 *
 * Same narrow scope as std hover/completion: a direct `std.name` access. `std['name']` and `local s = std; s.name`
 * aren't followed, and a local that shadows `std` is left alone.
 */
class JsonnetSjsonnetOnlyStdInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // Vendored libraries aren't the user's to edit (a re-install would overwrite the change).
        val path = holder.file.virtualFile?.path
        if (path != null && JsonnetStubIndexUtil.isVendoredPath(path)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is JsonnetDotSuffix) return
                val name = element.nameIdentifier ?: return
                val entry = SjsonnetOnlyStd.find(name.text) ?: return
                if (!StdLibRegistry.isStdMemberAccess(element) || isShadowed(element)) return
                holder.registerProblem(
                    name,
                    "'std.${entry.name}' exists only in sjsonnet (the preview); go-jsonnet, which Tanka runs, " +
                        "doesn't define it, so this fails under 'tk'. ${entry.portable}",
                )
            }
        }
    }

    /** `local std = ...; std.regexQuoteMeta(...)` is the user's own object, not the standard library. */
    private fun isShadowed(dotSuffix: JsonnetDotSuffix): Boolean {
        var receiver = dotSuffix.prevSibling
        while (receiver != null && receiver !is JsonnetNameRef) receiver = receiver.prevSibling
        return receiver is JsonnetNameRef && JsonnetResolver.resolveLocalName(receiver, "std") != null
    }
}
