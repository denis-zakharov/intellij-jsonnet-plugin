package com.dz.intellijjsonnet.editor.preview

import com.dz.intellijjsonnet.engine.JsonnetEngine
import com.dz.intellijjsonnet.lang.JsonnetFileType
import com.dz.intellijjsonnet.lang.LibsonnetFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Full-eval Preview: shows the embedded interpreter's JSON output for
 * whichever Jsonnet file is currently focused, auto-refreshing (debounced) as
 * you type. `ext-str`/`ext-code` vars are a simple `key=value`-per-line box;
 * TLA vars, YAML output, and output→source click-jump are follow-ups, not v1.
 */
class JsonnetPreviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val output = JBTextArea().apply {
        isEditable = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
    }
    private val extVarsField = EditorTextField("").apply {
        setOneLineMode(false)
        preferredSize = java.awt.Dimension(200, 60)
    }
    private var currentFile: VirtualFile? = null

    init {
        val header = JPanel(BorderLayout())
        header.add(JBLabel("ext vars (key=value, one per line):"), BorderLayout.NORTH)
        header.add(extVarsField, BorderLayout.CENTER)
        header.border = JBUI.Borders.empty(4)

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(output), BorderLayout.CENTER)

        val actionGroup = DefaultActionGroup()
        actionGroup.add(object : AnAction("Refresh", "Re-evaluate the current file", com.intellij.icons.AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        })
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actionGroup, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.WEST)

        extVarsField.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleRefresh()
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
        watchDocument(file)
        refresh()
    }

    private fun watchDocument(file: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (currentFile == file) scheduleRefresh()
                }
            },
            this,
        )
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, 400)
    }

    private fun parseExtVars(): Map<String, String> =
        extVarsField.text.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

    fun refresh() {
        val file = currentFile ?: run {
            output.text = "(no Jsonnet file focused)"
            return
        }
        val result = JsonnetEngine.evaluateFile(file, extVars = parseExtVars())
        output.text = when (result) {
            is JsonnetEngine.Result.Success -> result.json
            is JsonnetEngine.Result.Failure -> "Evaluation failed:\n${result.message}"
        }
    }

    override fun dispose() {
        // Alarm and document listeners were registered with `this` as their
        // parent Disposable, so they're torn down automatically once
        // Disposer.dispose(this) runs (see JsonnetPreviewToolWindowFactory).
    }
}
