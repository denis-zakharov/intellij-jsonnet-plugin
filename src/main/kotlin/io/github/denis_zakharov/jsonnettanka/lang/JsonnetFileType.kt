package io.github.denis_zakharov.jsonnettanka.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

sealed class JsonnetFileTypeBase(private val typeName: String, private val extension: String) : LanguageFileType(JsonnetLanguage) {
    override fun getName(): String = typeName

    // Both file types share one Language, so LanguageFileType's default display name
    // ("Jsonnet") would be identical for the two; FileTypeManagerImpl.checkUnique logs a
    // PluginException for that (and for an identical description) on every IDE start.
    override fun getDisplayName(): String = typeName
    override fun getDescription(): String = "$typeName file"
    override fun getDefaultExtension(): String = extension
    override fun getIcon(): Icon = JsonnetIcons.FILE
}

// Names here must match plugin.xml's <fileType name="..."> exactly — the
// platform's FileTypeManagerImpl asserts they're equal at registration time,
// which surfaces (via BasePlatformTestCase's strict TestLoggerFactory) as a
// StubIndexImpl initialization failure whose fallout is subtle: the failure
// wedges a CompletableFuture that BasePlatformTestCase.tearDown()'s leak
// check waits on with no timeout, hanging indefinitely rather than failing
// fast. See AGENTS.md's Testing section.
object JsonnetFileType : JsonnetFileTypeBase("Jsonnet", "jsonnet")
object LibsonnetFileType : JsonnetFileTypeBase("Libsonnet", "libsonnet")
