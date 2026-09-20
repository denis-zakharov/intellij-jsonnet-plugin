package com.dz.intellijjsonnet.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

sealed class JsonnetFileTypeBase(private val typeName: String, private val extension: String) : LanguageFileType(JsonnetLanguage) {
    override fun getName(): String = typeName
    override fun getDescription(): String = "Jsonnet file"
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
