package com.dz.intellijjsonnet.editor.preview

import com.dz.intellijjsonnet.engine.JsonnetEngine
import com.dz.intellijjsonnet.engine.VirtualFilePath
import com.dz.intellijjsonnet.lang.JsonnetFileType
import com.dz.intellijjsonnet.lang.LibsonnetFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.text.BadLocationException
import javax.swing.JPanel

/**
 * Full-eval Preview: shows the embedded interpreter's JSON (or YAML) output
 * for whichever Jsonnet file is currently focused, auto-refreshing (debounced)
 * as you type. Ext vars and TLA vars are each a one-variable-per-line box —
 * see [PreviewVars] for the `name=string` / `name:=code` syntax. Ctrl/Cmd+click
 * on an output line jumps to the source expression that produced that value
 * (possibly in an imported file) — see [PreviewOutputPaths] and
 * [com.dz.intellijjsonnet.engine.SourceLocator].
 */
class JsonnetPreviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val output = JBTextArea().apply {
        isEditable = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
    }
    private val extVarsField = varsField()
    private val tlaVarsField = varsField()
    private var yamlOutput = false
    private var currentFile: VirtualFile? = null

    /** The last successful render, kept for click-jump; `null` while the output area shows an error/placeholder instead. */
    private var lastSuccess: JsonnetEngine.Result.Success? = null
    private var lastFormat = JsonnetEngine.OutputFormat.JSON

    /** A file we just opened ourselves via click-jump: its selection event must not re-target Preview at it. */
    private var jumpTarget: VirtualFile? = null

    init {
        val header = JPanel(GridLayout(1, 2, JBUI.scale(8), 0))
        header.add(labeled("ext vars (name=string, name:=code):", extVarsField))
        header.add(labeled("TLA vars (name=string, name:=code):", tlaVarsField))
        header.border = JBUI.Borders.empty(4)

        output.toolTipText = "Ctrl/Cmd+click a line to jump to the source that produced it"
        output.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && (e.isControlDown || e.isMetaDown)) jumpToSource(e.point)
            }
        })

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(output), BorderLayout.CENTER)

        val actionGroup = DefaultActionGroup()
        actionGroup.add(object : AnAction("Refresh", "Re-evaluate the current file", com.intellij.icons.AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        })
        actionGroup.add(object : ToggleAction("YAML Output", "Render the output as YAML instead of JSON", com.intellij.icons.AllIcons.FileTypes.Yaml) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = yamlOutput
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                yamlOutput = state
                refresh()
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actionGroup, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.WEST)

        for (field in listOf(extVarsField, tlaVarsField)) {
            field.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = scheduleRefresh()
            })
        }

        watchJsonnetDocuments()

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
        if (file == jumpTarget) return
        if (file == currentFile) return
        currentFile = file
        refresh()
    }

    /**
     * Any Jsonnet buffer changing can change the focused file's output (it may import the one being
     * edited, e.g. in a split editor), and evaluation reads live buffers — see
     * [com.dz.intellijjsonnet.engine.VirtualFileText] — so listen to all of them, not just the focused one.
     */
    private fun watchJsonnetDocuments() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val type = FileDocumentManager.getInstance().getFile(event.document)?.fileType
                    if (type == JsonnetFileType || type == LibsonnetFileType) scheduleRefresh()
                }
            },
            this,
        )
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, 400)
    }

    private fun varsField() = EditorTextField("").apply {
        setOneLineMode(false)
        preferredSize = java.awt.Dimension(200, 60)
    }

    private fun labeled(label: String, field: EditorTextField) = JPanel(BorderLayout()).apply {
        add(JBLabel(label), BorderLayout.NORTH)
        add(field, BorderLayout.CENTER)
    }

    internal val outputText: String get() = output.text
    internal var yamlEnabled: Boolean
        get() = yamlOutput
        set(value) { yamlOutput = value }

    fun refresh() {
        lastSuccess = null
        val file = currentFile ?: run {
            output.text = "(no Jsonnet file focused)"
            return
        }
        val format = if (yamlOutput) JsonnetEngine.OutputFormat.YAML else JsonnetEngine.OutputFormat.JSON
        val result = JsonnetEngine.evaluateFile(
            file,
            extVars = PreviewVars.parse(extVarsField.text),
            tlaVars = PreviewVars.parse(tlaVarsField.text),
            format = format,
        )
        output.text = when (result) {
            is JsonnetEngine.Result.Success -> {
                lastSuccess = result
                lastFormat = format
                result.output
            }
            is JsonnetEngine.Result.Failure -> "Evaluation failed:\n${result.message}"
        }
        output.caretPosition = 0
    }

    private fun jumpToSource(point: Point) {
        val line = try {
            output.getLineOfOffset(output.viewToModel2D(point))
        } catch (e: BadLocationException) {
            return
        }
        jumpToLine(line)
    }

    /** Maps the 0-based output [line] to a value path, asks sjsonnet where that value came from, and opens it. */
    internal fun jumpToLine(line: Int) {
        val success = lastSuccess ?: return
        val locator = success.locator ?: return
        val segments = PreviewOutputPaths.pathForLine(output.text, lastFormat, line) ?: return
        val location = locator.locate(segments) ?: return
        val target = (location.path as? VirtualFilePath)?.file ?: return

        jumpTarget = target
        OpenFileDescriptor(project, target, location.offset).navigate(true)
        // The selection event (if any) fires synchronously above; don't let a stale marker swallow a later real focus.
        ApplicationManager.getApplication().invokeLater { jumpTarget = null }
    }

    override fun dispose() {
        // Alarm and document listeners were registered with `this` as their
        // parent Disposable, so they're torn down automatically once
        // Disposer.dispose(this) runs (see JsonnetPreviewToolWindowFactory).
    }
}
