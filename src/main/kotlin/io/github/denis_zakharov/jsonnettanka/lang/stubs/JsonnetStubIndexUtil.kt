package io.github.denis_zakharov.jsonnettanka.lang.stubs

import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetBind
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetField
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetFile
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetLocalExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetObjectLiteral
import com.intellij.psi.PsiElement

/**
 * Decides which `bind`/`field` declarations earn a global stub-index entry.
 * Scoped to a file's *top-level* expression tree — the leading `local` chain,
 * the (possibly `+`-composed) object literal(s) it or the file's root
 * expression evaluates to, and fields reachable from there — mirroring how a
 * Tanka/Jsonnet library exposes its public API (`local New(x) = {...};`-style
 * definitions). Deliberately excludes anything nested inside a function body,
 * array literal/comprehension, or call argument: those are implementation
 * detail, not something worth a project-wide "Go to Symbol" hit, and indexing
 * them would defeat the point of a *stub* index (bounded, cheap) on a large
 * vendored tree — see the plan doc's §6 risk note on huge vendored
 * `jsonnet-libs` checkouts. Anything under a `vendor/` directory (jb-installed
 * dependencies, per the plan's §5 directory conventions) is excluded outright,
 * for the same reason.
 */
object JsonnetStubIndexUtil {

    fun isTopLevelBindDecl(bind: JsonnetBind): Boolean {
        if (isVendored(bind)) return false
        val localExpr = bind.parent as? JsonnetLocalExpr ?: return false
        val expr = localExpr.parent as? JsonnetExpr ?: return false
        return isTopLevelExpr(expr)
    }

    fun isTopLevelFieldDecl(field: JsonnetField): Boolean {
        if (isVendored(field)) return false
        val objectLiteral = field.parent?.parent as? JsonnetObjectLiteral ?: return false
        val expr = objectLiteral.parent as? JsonnetExpr ?: return false
        return isTopLevelExpr(expr)
    }

    private fun isTopLevelExpr(expr: JsonnetExpr): Boolean {
        return when (val parent = expr.parent) {
            is JsonnetFile -> true
            is JsonnetLocalExpr -> parent.expr === expr && (parent.parent as? JsonnetExpr)?.let(::isTopLevelExpr) == true
            // A non-null paramList means `bind`/`field` is function-sugar
            // (`f(x) = ...` / `f(x): ...`) — its expr is a function body, not
            // a plain value, so anything nested in it is implementation
            // detail, not part of the top-level tree (see the class doc).
            is JsonnetBind -> parent.expr === expr && parent.paramList == null && isTopLevelBindDecl(parent)
            is JsonnetField -> parent.expr === expr && parent.paramList == null && isTopLevelFieldDecl(parent)
            else -> false
        }
    }

    private fun isVendored(element: PsiElement): Boolean {
        val path = element.containingFile?.originalFile?.virtualFile?.path ?: return false
        return isVendoredPath(path)
    }

    /** Standalone (no PSI/VFS needed) so it's directly unit-testable. */
    fun isVendoredPath(path: String): Boolean = path.split('/').any { it == "vendor" }
}
