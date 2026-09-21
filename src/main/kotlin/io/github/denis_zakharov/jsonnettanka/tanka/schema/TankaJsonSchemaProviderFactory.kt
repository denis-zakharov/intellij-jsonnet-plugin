package io.github.denis_zakharov.jsonnettanka.tanka.schema

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Backs `spec.json` (Tanka environments) and `jsonnetfile.json` (jsonnet-bundler
 * manifests) with real JSON Schemas for completion/validation — both file
 * formats the plan (§5) calls out as needing first-class editing support.
 */
class TankaJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(
        NamedResourceSchemaProvider("Tanka Environment", "spec.json", "/schemas/tanka-spec.schema.json"),
        NamedResourceSchemaProvider("jsonnet-bundler", "jsonnetfile.json", "/schemas/jsonnetfile.schema.json"),
    )
}

private class NamedResourceSchemaProvider(
    private val displayName: String,
    private val fileName: String,
    private val resourcePath: String,
) : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean = file.name == fileName

    override fun getName(): String = displayName

    override fun getSchemaFile(): VirtualFile? {
        val url = javaClass.getResource(resourcePath) ?: return null
        return VfsUtil.findFileByURL(url)
    }

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
