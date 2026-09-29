package io.github.denis_zakharov.jsonnettanka.imports

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetFileType
import io.github.denis_zakharov.jsonnettanka.lang.LibsonnetFileType
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetImportExpr
import io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes
import io.github.denis_zakharov.jsonnettanka.lang.stubs.JsonnetStubIndexUtil
import io.github.denis_zakharov.jsonnettanka.tanka.TankaJpath
import io.github.denis_zakharov.jsonnettanka.tanka.TankaTkModule
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

enum class ImportKind(val keyword: String) {
    IMPORT("import"),
    IMPORTSTR("importstr"),
    IMPORTBIN("importbin"),
}

/** One `import`/`importstr`/`importbin` expression: what it names, where that resolved (if anywhere), and where it sits in its file. */
data class ImportEdge(val kind: ImportKind, val name: String, val target: VirtualFile?, val offset: Int) {
    val isTkModule: Boolean get() = TankaTkModule.isTkImportPath(name)
}

enum class NodeStatus {
    NORMAL,

    /** A `vendor/` file reached from non-vendor code; shown but not expanded (see [JsonnetImportGraph]). */
    VENDOR_COLLAPSED,

    /** Already on the path from the root — expanding it would loop forever. */
    CYCLE,

    /** Already expanded elsewhere in the tree; not repeated, so shared dependencies don't blow the tree up. */
    SEEN_ABOVE,

    UNRESOLVED,

    /** `import 'tk'` — Tanka's synthetic environment module, not a file. */
    TK_MODULE,
}

/**
 * A row in the import tree. [file] is null for unresolved imports and the `tk` module, in which
 * case [label] is the import string as written; [sourceFile]/[sourceOffset] locate the import
 * expression that produced this row (null for the root).
 */
class ImportNode(
    val file: VirtualFile?,
    val label: String,
    val kind: ImportKind?,
    val status: NodeStatus,
    val children: List<ImportNode>,
    val sourceFile: VirtualFile?,
    val sourceOffset: Int,
)

/**
 * ADR 0006: the lightweight import-graph view's model — deliberately not the
 * `com.intellij.diagram` `DiagramProvider` framework. Resolution goes through
 * [TankaJpath.resolveImport], the same rule evaluation uses, so the graph can't disagree with what
 * `import` will actually load.
 *
 * Both directions produce trees over what is really a graph, so two rules keep them finite and
 * readable: a file already on the path from the root is marked [NodeStatus.CYCLE] and not expanded,
 * and a file already expanded elsewhere is marked [NodeStatus.SEEN_ABOVE] (first occurrence in
 * depth-first order gets the subtree). And unless `expandVendor` is set, the tree never crosses from
 * project code into `vendor/` (a Tanka project's jb-installed dependencies — k8s-libsonnet alone is
 * hundreds of files): vendored imports appear as leaves and vendored importers are omitted.
 *
 * Must be built and queried inside a read action. Edges are parsed from PSI (the editor's
 * committed buffers, not just what's on disk) and memoized per instance — build a fresh graph per
 * refresh.
 */
class JsonnetImportGraph(private val project: Project) {

    private val edgeCache = HashMap<VirtualFile, List<ImportEdge>>()

    private val importers: Map<VirtualFile, List<Pair<VirtualFile, ImportEdge>>> by lazy {
        val result = LinkedHashMap<VirtualFile, MutableList<Pair<VirtualFile, ImportEdge>>>()
        for (file in allJsonnetFiles()) {
            for (edge in edgesOf(file)) {
                val target = edge.target ?: continue
                result.getOrPut(target) { ArrayList() }.add(file to edge)
            }
        }
        result
    }

    fun edgesOf(file: VirtualFile): List<ImportEdge> = edgeCache.getOrPut(file) { parseEdges(file) }

    private fun parseEdges(file: VirtualFile): List<ImportEdge> {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        return PsiTreeUtil.findChildrenOfType(psi, JsonnetImportExpr::class.java).mapNotNull { expr ->
            val keyword = expr.firstChild?.node?.elementType
            val kind = when (keyword) {
                JsonnetTypes.IMPORT_KW -> ImportKind.IMPORT
                JsonnetTypes.IMPORTSTR_KW -> ImportKind.IMPORTSTR
                JsonnetTypes.IMPORTBIN_KW -> ImportKind.IMPORTBIN
                else -> return@mapNotNull null
            }
            val literal = expr.node.findChildByType(JsonnetTypes.STRING) ?: return@mapNotNull null
            val name = unquote(literal.text) ?: return@mapNotNull null
            val target = TankaJpath.resolveImport(file, name)?.takeIf { !it.isDirectory }
            ImportEdge(kind, name, target, expr.textRange.startOffset)
        }
    }

    /** Sorted by path: the index returns files in no defined order, and the tree should be stable between refreshes. */
    private fun allJsonnetFiles(): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        return (FileTypeIndex.getFiles(JsonnetFileType, scope) + FileTypeIndex.getFiles(LibsonnetFileType, scope)).sortedBy { it.path }
    }

    /** Everything [root] imports, transitively. */
    fun imports(root: VirtualFile, expandVendor: Boolean): ImportNode {
        val seen = HashSet<VirtualFile>().also { it.add(root) }

        fun expand(file: VirtualFile, onPath: Set<VirtualFile>): List<ImportNode> =
            edgesOf(file).map { edge ->
                val target = edge.target
                when {
                    edge.isTkModule -> leaf(null, edge, NodeStatus.TK_MODULE, file)
                    target == null -> leaf(null, edge, NodeStatus.UNRESOLVED, file)
                    target in onPath -> leaf(target, edge, NodeStatus.CYCLE, file)
                    crossesIntoVendor(file, target, expandVendor) -> leaf(target, edge, NodeStatus.VENDOR_COLLAPSED, file)
                    !seen.add(target) -> leaf(target, edge, NodeStatus.SEEN_ABOVE, file)
                    else -> ImportNode(target, target.name, edge.kind, NodeStatus.NORMAL, expand(target, onPath + target), file, edge.offset)
                }
            }

        return ImportNode(root, root.name, null, NodeStatus.NORMAL, expand(root, setOf(root)), null, 0)
    }

    /** Every file that imports [root], transitively (who breaks if [root] changes). */
    fun importedBy(root: VirtualFile, expandVendor: Boolean): ImportNode {
        val seen = HashSet<VirtualFile>().also { it.add(root) }

        fun expand(file: VirtualFile, onPath: Set<VirtualFile>): List<ImportNode> =
            importers[file].orEmpty().mapNotNull { (importer, edge) ->
                when {
                    crossesIntoVendor(file, importer, expandVendor) -> null
                    importer in onPath -> leaf(importer, edge, NodeStatus.CYCLE, importer)
                    !seen.add(importer) -> leaf(importer, edge, NodeStatus.SEEN_ABOVE, importer)
                    else -> ImportNode(importer, importer.name, edge.kind, NodeStatus.NORMAL, expand(importer, onPath + importer), importer, edge.offset)
                }
            }

        return ImportNode(root, root.name, null, NodeStatus.NORMAL, expand(root, setOf(root)), null, 0)
    }

    private fun leaf(target: VirtualFile?, edge: ImportEdge, status: NodeStatus, source: VirtualFile) =
        ImportNode(target, target?.name ?: edge.name, edge.kind, status, emptyList(), source, edge.offset)

    private fun crossesIntoVendor(from: VirtualFile, to: VirtualFile, expandVendor: Boolean): Boolean =
        !expandVendor && isVendored(to) && !isVendored(from)

    private fun isVendored(file: VirtualFile) = JsonnetStubIndexUtil.isVendoredPath(file.path)

    companion object {
        /** The import path as written: strips an optional verbatim `@` prefix and the surrounding quotes. Escapes are left as-is (import paths don't use them in practice). */
        fun unquote(tokenText: String): String? {
            val t = tokenText.removePrefix("@")
            if (t.length < 2) return null
            val quote = t[0]
            if ((quote != '\'' && quote != '"') || t.last() != quote) return null
            return t.substring(1, t.length - 1)
        }
    }
}
