package io.github.denis_zakharov.jsonnettanka.imports

import io.github.denis_zakharov.jsonnettanka.lang.JsonnetFileType
import io.github.denis_zakharov.jsonnettanka.lang.LibsonnetFileType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * ADR 0006: read-only import-graph view for whichever Jsonnet file is focused — a tree of
 * everything it imports (transitively) or, with the "Imported By" toggle, everything that imports
 * it. Double-click / Enter opens the file (or, for an unresolved import, the import expression
 * itself). The model is [JsonnetImportGraph]; this is only the shell around it.
 */
class JsonnetImportGraphPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel).apply {
        isRootVisible = true
        cellRenderer = NodeRenderer()
    }
    private val hint = JBLabel("(no Jsonnet file focused)").apply { border = JBUI.Borders.empty(6) }

    private var currentFile: VirtualFile? = null
    private var showImporters = false
    private var expandVendor = false

    init {
        val actions = DefaultActionGroup()
        actions.add(object : AnAction("Refresh", "Rebuild the import tree", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        })
        actions.add(object : ToggleAction("Imported By", "Show the files that import this one instead of the files it imports", AllIcons.Hierarchy.Supertypes) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = showImporters
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showImporters = state
                refresh()
            }
        })
        actions.add(object : ToggleAction("Expand vendor/", "Also expand into (and, for Imported By, include) files under vendor/", AllIcons.Nodes.Folder) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = expandVendor
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                expandVendor = state
                refresh()
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this

        add(toolbar.component, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(hint, BorderLayout.SOUTH)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelected()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    onFileFocused(event.newFile)
                }
            },
        )
        onFileFocused(FileEditorManager.getInstance(project).selectedEditor?.file)
    }

    private fun onFileFocused(file: VirtualFile?) {
        if (file == null || (file.fileType != JsonnetFileType && file.fileType != LibsonnetFileType)) return
        if (file == currentFile) return
        currentFile = file
        refresh()
    }

    /** Builds off the EDT (a project-wide scan for "Imported By" can be slow) and cancels any build still in flight. */
    fun refresh() {
        val file = currentFile ?: return
        val (importers, vendor) = showImporters to expandVendor
        ReadAction.nonBlocking<ImportNode> { build(file, importers, vendor) }
            .inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { show(it, importers) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Synchronous variant for tests. */
    internal fun refreshNow() {
        val file = currentFile ?: return
        show(ApplicationManager.getApplication().runReadAction<ImportNode> { build(file, showImporters, expandVendor) }, showImporters)
    }

    private fun build(file: VirtualFile, importers: Boolean, vendor: Boolean): ImportNode {
        val graph = JsonnetImportGraph(project)
        return if (importers) graph.importedBy(file, vendor) else graph.imports(file, vendor)
    }

    private fun show(root: ImportNode, importers: Boolean) {
        treeModel.setRoot(toTreeNode(root))
        TreeUtil.expandAll(tree)
        val count = countNodes(root) - 1
        hint.text = when {
            count == 0 && importers -> "Nothing imports ${root.label}."
            count == 0 -> "${root.label} has no imports."
            importers -> "$count importer(s), transitively. Double-click or Enter to open."
            else -> "$count import(s), transitively. Double-click or Enter to open."
        }
    }

    private fun toTreeNode(node: ImportNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).also { treeNode -> node.children.forEach { treeNode.add(toTreeNode(it)) } }

    private fun countNodes(node: ImportNode): Int = 1 + node.children.sumOf(::countNodes)

    internal val rootNode: ImportNode? get() = (treeModel.root as? DefaultMutableTreeNode)?.userObject as? ImportNode

    private fun openSelected() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ImportNode ?: return
        val target = node.file
        when {
            target != null -> OpenFileDescriptor(project, target).navigate(true)
            node.sourceFile != null -> OpenFileDescriptor(project, node.sourceFile, node.sourceOffset).navigate(true)
        }
    }

    override fun dispose() {}

    private inner class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? ImportNode ?: return
            icon = node.file?.fileType?.icon ?: AllIcons.General.Warning
            val unresolved = node.status == NodeStatus.UNRESOLVED
            append(node.label, if (unresolved) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (node.kind != null && node.kind != ImportKind.IMPORT) {
                append("  ${node.kind.keyword}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
            statusText(node.status)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            node.file?.parent?.let { append("  ${relativeDir(it)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        }
    }

    private fun relativeDir(dir: VirtualFile): String {
        val base = project.guessProjectDir() ?: return dir.path
        return VfsUtilCore.getRelativePath(dir, base) ?: dir.path
    }

    companion object {
        internal fun statusText(status: NodeStatus): String? = when (status) {
            NodeStatus.NORMAL -> null
            NodeStatus.VENDOR_COLLAPSED -> "vendor — not expanded"
            NodeStatus.CYCLE -> "↻ cycle"
            NodeStatus.SEEN_ABOVE -> "shown above"
            NodeStatus.UNRESOLVED -> "unresolved"
            NodeStatus.TK_MODULE -> "Tanka virtual module"
        }
    }
}
