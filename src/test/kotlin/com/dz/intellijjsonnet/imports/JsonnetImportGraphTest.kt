package com.dz.intellijjsonnet.imports

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JsonnetImportGraphTest : BasePlatformTestCase() {

    private fun add(path: String, text: String) = myFixture.addFileToProject(path, text).virtualFile

    /** One line per node, two spaces per depth: `name [STATUS] (kind)` — NORMAL and plain `import` are left out. */
    private fun render(node: ImportNode, depth: Int = 0): String = buildString {
        append("  ".repeat(depth)).append(node.label)
        if (node.status != NodeStatus.NORMAL) append(" [").append(node.status).append("]")
        if (node.kind != null && node.kind != ImportKind.IMPORT) append(" (").append(node.kind.keyword).append(")")
        append("\n")
        node.children.forEach { append(render(it, depth + 1)) }
    }

    fun `test outgoing imports are resolved relative to the importing file, transitively, with kinds`() {
        add("lib/b.libsonnet", "{ b: 1 }")
        add("lib/a.libsonnet", "local b = import 'b.libsonnet'; { a: b }")
        add("data.txt", "hello")
        val main = add("main.jsonnet", "{ a: (import 'lib/a.libsonnet'), text: importstr 'data.txt' }")

        val graph = JsonnetImportGraph(project)
        assertEquals(
            """
            main.jsonnet
              a.libsonnet
                b.libsonnet
              data.txt (importstr)

            """.trimIndent(),
            render(graph.imports(main, expandVendor = false)),
        )
    }

    fun `test unresolved imports and the tk module are leaves that say so`() {
        val main = add("main.jsonnet", "local tk = import 'tk'; { a: import 'missing.libsonnet' }")
        val root = JsonnetImportGraph(project).imports(main, expandVendor = false)
        assertEquals("main.jsonnet\n  tk [TK_MODULE]\n  missing.libsonnet [UNRESOLVED]\n", render(root))
        assertNull(root.children[1].file)
        assertEquals(main, root.children[1].sourceFile)
        assertEquals("local tk = import 'tk'; { a: import 'missing.libsonnet' }".indexOf("import 'missing"), root.children[1].sourceOffset)
    }

    fun `test a directory is not a resolved import`() {
        add("dir/x.libsonnet", "1")
        val main = add("main.jsonnet", "import 'dir'")
        assertEquals("main.jsonnet\n  dir [UNRESOLVED]\n", render(JsonnetImportGraph(project).imports(main, expandVendor = false)))
    }

    fun `test an import cycle terminates and is marked`() {
        add("b.libsonnet", "import 'a.libsonnet'")
        val a = add("a.libsonnet", "import 'b.libsonnet'")
        assertEquals(
            "a.libsonnet\n  b.libsonnet\n    a.libsonnet [CYCLE]\n",
            render(JsonnetImportGraph(project).imports(a, expandVendor = false)),
        )
    }

    fun `test a shared dependency is expanded once and marked seen afterwards`() {
        add("shared.libsonnet", "import 'leaf.libsonnet'")
        add("leaf.libsonnet", "1")
        add("x.libsonnet", "import 'shared.libsonnet'")
        add("y.libsonnet", "import 'shared.libsonnet'")
        val main = add("main.jsonnet", "[import 'x.libsonnet', import 'y.libsonnet']")
        assertEquals(
            """
            main.jsonnet
              x.libsonnet
                shared.libsonnet
                  leaf.libsonnet
              y.libsonnet
                shared.libsonnet [SEEN_ABOVE]

            """.trimIndent(),
            render(JsonnetImportGraph(project).imports(main, expandVendor = false)),
        )
    }

    private fun tankaProject(): com.intellij.openapi.vfs.VirtualFile {
        add("jsonnetfile.json", "{}")
        add("vendor/k.libsonnet", "import 'inner.libsonnet'")
        add("vendor/inner.libsonnet", "{}")
        add("lib/util.libsonnet", "import 'k.libsonnet'")
        return add("environments/e/main.jsonnet", "[import 'k.libsonnet', import 'util.libsonnet']")
    }

    fun `test jpath imports resolve and vendor is shown but not expanded by default`() {
        val main = tankaProject()
        assertEquals(
            """
            main.jsonnet
              k.libsonnet [VENDOR_COLLAPSED]
              util.libsonnet
                k.libsonnet [VENDOR_COLLAPSED]

            """.trimIndent(),
            render(JsonnetImportGraph(project).imports(main, expandVendor = false)),
        )
    }

    fun `test expanding vendor shows what the vendored library imports`() {
        val main = tankaProject()
        assertEquals(
            """
            main.jsonnet
              k.libsonnet
                inner.libsonnet
              util.libsonnet
                k.libsonnet [SEEN_ABOVE]

            """.trimIndent(),
            render(JsonnetImportGraph(project).imports(main, expandVendor = true)),
        )
    }

    fun `test a vendored root expands its own vendored imports`() {
        tankaProject()
        val k = myFixture.findFileInTempDir("vendor/k.libsonnet")
        assertEquals("k.libsonnet\n  inner.libsonnet\n", render(JsonnetImportGraph(project).imports(k, expandVendor = false)))
    }

    fun `test importedBy lists importers transitively, with cycles and shared importers marked`() {
        val target = add("target.libsonnet", "{}")
        add("a.libsonnet", "import 'target.libsonnet'")
        add("b.libsonnet", "local t = import 'target.libsonnet'; importstr 'c.txt'")
        add("c.jsonnet", "[import 'a.libsonnet', import 'b.libsonnet']")
        assertEquals(
            """
            target.libsonnet
              a.libsonnet
                c.jsonnet
              b.libsonnet
                c.jsonnet [SEEN_ABOVE]

            """.trimIndent(),
            render(JsonnetImportGraph(project).importedBy(target, expandVendor = false)),
        )

        add("loop1.libsonnet", "import 'loop2.libsonnet'")
        val loop2 = add("loop2.libsonnet", "import 'loop1.libsonnet'")
        assertEquals(
            "loop2.libsonnet\n  loop1.libsonnet\n    loop2.libsonnet [CYCLE]\n",
            render(JsonnetImportGraph(project).importedBy(loop2, expandVendor = false)),
        )
    }

    fun `test importedBy from a vendored file stays in bounds, walking up through project files`() {
        tankaProject()
        val inner = myFixture.findFileInTempDir("vendor/inner.libsonnet")
        assertEquals(
            """
            inner.libsonnet
              k.libsonnet
                main.jsonnet
                util.libsonnet
                  main.jsonnet [SEEN_ABOVE]

            """.trimIndent(),
            render(JsonnetImportGraph(project).importedBy(inner, expandVendor = false)),
        )
    }

    fun `test importedBy omits vendored importers of a project file unless asked`() {
        tankaProject()
        add("vendor/uses_util.libsonnet", "import '../lib/util.libsonnet'")
        val util = myFixture.findFileInTempDir("lib/util.libsonnet")

        assertEquals("util.libsonnet\n  main.jsonnet\n", render(JsonnetImportGraph(project).importedBy(util, expandVendor = false)))
        assertEquals(
            "util.libsonnet\n  main.jsonnet\n  uses_util.libsonnet\n",
            render(JsonnetImportGraph(project).importedBy(util, expandVendor = true)),
        )
    }

    fun `test unquote handles quote styles and verbatim strings`() {
        assertEquals("a/b.libsonnet", JsonnetImportGraph.unquote("'a/b.libsonnet'"))
        assertEquals("a.libsonnet", JsonnetImportGraph.unquote("\"a.libsonnet\""))
        assertEquals("a.libsonnet", JsonnetImportGraph.unquote("@'a.libsonnet'"))
        assertNull(JsonnetImportGraph.unquote("'"))
        assertNull(JsonnetImportGraph.unquote("|||\nx\n|||"))
    }

    // --- Panel wiring ---

    private fun panelFor(mainPath: String): JsonnetImportGraphPanel {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(mainPath))
        val panel = JsonnetImportGraphPanel(project)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, panel)
        return panel
    }

    fun `test panel follows the focused file and builds both directions`() {
        add("lib.libsonnet", "{}")
        add("main.jsonnet", "import 'lib.libsonnet'")

        val panel = panelFor("main.jsonnet")
        panel.refreshNow()
        assertEquals("main.jsonnet", panel.rootNode?.label)
        assertEquals(listOf("lib.libsonnet"), panel.rootNode?.children?.map { it.label })

        val lib = panelFor("lib.libsonnet")
        lib.refreshNow()
        assertEquals(emptyList<String>(), lib.rootNode?.children?.map { it.label })
    }

    fun `test every status has display text except NORMAL`() {
        for (status in NodeStatus.values()) {
            assertEquals(status != NodeStatus.NORMAL, JsonnetImportGraphPanel.statusText(status) != null)
        }
    }

    fun `test both tool window and action are registered`() {
        assertNotNull(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("Jsonnet.ShowImportGraph"))
        // Light fixtures don't instantiate tool windows, so check the extension registration itself.
        val ids = com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList.map { it.id }
        assertTrue("registered tool windows: $ids", "Jsonnet Imports" in ids)
    }
}
