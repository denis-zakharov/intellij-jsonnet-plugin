package io.github.denis_zakharov.jsonnettanka.platform

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Hover on functions documented with docsonnet. The annotations below are copied from real libraries
 * (`javaapp`, k8s-libsonnet's generated `_gen/`, `xtd`) rather than invented, since the generators and the
 * hand-written style differ in ways a made-up example wouldn't show: positional vs named arguments,
 * JSON-quoted help, text blocks.
 */
class DocsonnetHoverTest : BasePlatformTestCase() {

    private val javaapp = """
        local d = import 'doc-util/main.libsonnet';
        {
          '#withPort':: d.fn(
            |||
              `withPort` adds (or replaces) one container port, named `name`.

              Pass `exposed=false` to keep it container-only.
            |||,
            [
              d.arg('name', d.T.string),
              d.arg('port', d.T.int),
              d.arg('exposed', d.T.boolean, true),
              d.arg('protocol', d.T.string, 'TCP'),
            ]
          ),
          withPort(name, port, exposed=true, protocol='TCP'):: { ports+:: { [name]: { containerPort: port } } },
        }
    """.trimIndent()

    private fun hoverInMain(main: String): String? {
        myFixture.configureByText("main.jsonnet", main)
        return hoverAtCaret(myFixture)
    }

    fun `test hover on a call shows signature, help and typed parameters`() {
        myFixture.addFileToProject("javaapp.libsonnet", javaapp)
        val doc = hoverInMain("local javaapp = import 'javaapp.libsonnet'; javaapp.withP<caret>ort('http', 80)")!!
        assertTrue(doc, doc.contains("fn withPort(name, port, exposed=true, protocol='TCP')"))
        assertTrue(doc, doc.contains("adds (or replaces) one container port, named name."))
        assertTrue(doc, doc.contains("Pass exposed=false to keep it container-only."))
        assertTrue(doc, doc.contains("name string"))
        assertTrue(doc, doc.contains("port number"))
        assertTrue(doc, doc.contains("exposed bool, default true"))
        assertTrue(doc, doc.contains("protocol string, default 'TCP'"))
    }

    fun `test hover on the declaration itself shows the same documentation`() {
        myFixture.configureByText("javaapp.libsonnet", javaapp.replace("  withPort(", "  with<caret>Port("))
        val doc = hoverAtCaret(myFixture)!!
        assertTrue(doc, doc.contains("fn withPort("))
        assertTrue(doc, doc.contains("Pass exposed=false"))
    }

    fun `test k8s-libsonnet style - named arguments and JSON-quoted help`() {
        myFixture.addFileToProject(
            "deployment.libsonnet",
            """
            local d = import 'doc-util/main.libsonnet';
            {
              metadata: {
                '#withLabelsMixin':: d.fn(help='"Map of string keys and values that can be used to organize and categorize (scope and select) objects. More info: https://kubernetes.io/docs/concepts/overview/working-with-objects/labels"\n\n**Note:** This function appends passed data to existing values', args=[d.arg(name='labels', type=d.T.object)]),
                withLabelsMixin(labels): { metadata+: { labels+: labels } },
              },
            }
            """.trimIndent(),
        )
        val doc = hoverInMain("local deployment = import 'deployment.libsonnet'; deployment.metadata.withLabelsM<caret>ixin({})")!!
        assertTrue(doc, doc.contains("fn withLabelsMixin(labels)"))
        // The generator's JSON quoting is unwrapped: no leading quote, the text before `Note` intact.
        assertTrue(doc, doc.contains("Map of string keys and values"))
        assertFalse(doc, doc.contains("\"Map of string keys"))
        assertTrue(doc, doc.contains("Note: This function appends passed data to existing values"))
        assertTrue(doc, doc.contains("labels object"))
    }

    fun `test xtd style - visible annotation key, positional help and a text block`() {
        myFixture.addFileToProject(
            "date.libsonnet",
            """
            local d = import 'doc-util/main.libsonnet';
            {
              '#dayOfYear': d.fn(
                |||
                  `dayOfYear` calculates the ordinal day of the year based on the given date. The range of outputs is 1-365
                  for common years, and 1-366 for leap years.
                |||,
                [
                  d.arg('year', d.T.number),
                  d.arg('month', d.T.number),
                ],
              ),
              dayOfYear(year, month):: 1,
            }
            """.trimIndent(),
        )
        val doc = hoverInMain("local date = import 'date.libsonnet'; date.dayOf<caret>Year(2024, 1)")!!
        assertTrue(doc, doc.contains("fn dayOfYear(year, month)"))
        assertTrue(doc, doc.contains("calculates the ordinal day of the year"))
        assertTrue(doc, doc.contains("year number"))
    }

    // `javaapp.new`'s help embeds a fenced example; it should read as code, not as literal backticks.
    fun `test a fenced example in the help is rendered as a code block`() {
        myFixture.addFileToProject(
            "lib.libsonnet",
            """
            local d = import 'doc-util/main.libsonnet';
            {
              '#new':: d.fn(
                |||
                  `new` builds the app, e.g.:

                  ```jsonnet
                  javaapp.new('app-one', 'app-one:1.0.0')
                  + javaapp.withPort('http', 8080)
                  ```
                |||,
                [d.arg('name', d.T.string)]
              ),
              new(name):: {},
            }
            """.trimIndent(),
        )
        val doc = hoverInMain("local l = import 'lib.libsonnet'; l.ne<caret>w('a')")!!
        assertTrue(doc, doc.contains("javaapp.new('app-one', 'app-one:1.0.0')"))
        assertTrue(doc, doc.contains("+ javaapp.withPort('http', 8080)"))
        assertFalse(doc, doc.contains("```"))
    }

    fun `test objects and values`() {
        myFixture.addFileToProject(
            "images.libsonnet",
            """
            local d = import 'doc-util/main.libsonnet';
            {
              '#images':: d.obj(help='`images` provides images for common webservers'),
              images: {
                '#apache':: d.val(d.T.string, 'Apache HTTP webserver'),
                apache: 'httpd:2.4',
              },
            }
            """.trimIndent(),
        )
        val obj = hoverInMain("local i = import 'images.libsonnet'; i.ima<caret>ges")!!
        assertTrue(obj, obj.contains("obj images"))
        assertTrue(obj, obj.contains("provides images for common webservers"))

        val value = hoverInMain("local i = import 'images.libsonnet'; i.images.apa<caret>che")!!
        assertTrue(value, value.contains("val apache: string"))
        assertTrue(value, value.contains("Apache HTTP webserver"))
    }

    // k8s-libsonnet: the generated `deployment.new(name)` and the extension `deployment.new(name, replicas, containers)`
    // are composed with `+`, so a call resolves to both; the later one is what runs.
    fun `test a call that resolves to several composed definitions documents the last one`() {
        myFixture.addFileToProject(
            "gen.libsonnet",
            "local d = import 'doc-util/main.libsonnet'; { '#new':: d.fn('generated new', [d.arg('name', d.T.string)]), new(name):: {} }",
        )
        myFixture.addFileToProject(
            "ext.libsonnet",
            "local d = import 'doc-util/main.libsonnet'; { '#new':: d.fn('extended new', [d.arg('name', d.T.string), d.arg('replicas', d.T.int, 1)]), new(name, replicas=1):: {} }",
        )
        val doc = hoverInMain(
            "local gen = import 'gen.libsonnet'; local ext = import 'ext.libsonnet'; (gen + ext).ne<caret>w('a')",
        )!!
        assertTrue(doc, doc.contains("fn new(name, replicas=1)"))
        assertTrue(doc, doc.contains("extended new"))
        assertFalse(doc, doc.contains("generated new"))
    }

    // k8s-libsonnet `_custom/apps.libsonnet`: the generated docstring is kept and only the args are replaced.
    private val k8sGen = """
        local d = import 'doc-util/main.libsonnet';
        {
          deployment: {
            '#new':: d.fn(help='new returns an instance of Deployment', args=[d.arg(name='name', type=d.T.string)]),
            new(name):: { apiVersion: 'apps/v1', kind: 'Deployment' },
          },
        }
    """.trimIndent()

    private val k8sCustom = """
        local d = import 'doc-util/main.libsonnet';
        {
          deployment+: {
            '#new'+: d.func.withArgs([
              d.arg('name', d.T.string),
              d.arg('replicas', d.T.int, 1),
              d.arg('containers', d.T.array),
              d.arg('podLabels', d.T.object, {}),
            ]),
            new(name, replicas=1, containers=error 'containers unset', podLabels={}):: super.new(name),
          },
        }
    """.trimIndent()

    fun `test a withArgs modifier keeps the base help and replaces the parameters`() {
        myFixture.addFileToProject("gen.libsonnet", k8sGen)
        myFixture.addFileToProject("custom.libsonnet", k8sCustom)
        val doc = hoverInMain(
            "local k = (import 'gen.libsonnet') + (import 'custom.libsonnet'); k.deployment.ne<caret>w('a', 1, [])",
        )!!
        assertTrue(doc, doc.contains("fn new(name, replicas=1, containers=error 'containers unset', podLabels={})"))
        assertTrue(doc, doc.contains("new returns an instance of Deployment"))
        assertTrue(doc, doc.contains("replicas number, default 1"))
        assertTrue(doc, doc.contains("podLabels object, default {}"))
        assertTrue(doc, doc.contains("containers array"))
    }

    fun `test hovering the modifier's own declaration shows what it says`() {
        myFixture.configureByText("custom.libsonnet", k8sCustom.replace("    new(name, replicas", "    ne<caret>w(name, replicas"))
        val doc = hoverAtCaret(myFixture)!!
        assertTrue(doc, doc.contains("replicas number, default 1"))
    }

    fun `test withHelp chained with plus edits the help`() {
        myFixture.addFileToProject(
            "lib.libsonnet",
            """
            local d = import 'doc-util/main.libsonnet';
            {
              '#f':: d.fn('old help', [d.arg('x', d.T.string)]),
              f(x):: x,
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "patch.libsonnet",
            "local d = import 'doc-util/main.libsonnet'; { '#f'+: d.func.withHelp('new help') + d.func.withArgs([d.arg('y', d.T.int)]), f(y):: y }",
        )
        val doc = hoverInMain("local l = (import 'lib.libsonnet') + (import 'patch.libsonnet'); l.<caret>f(1)")!!
        assertTrue(doc, doc.contains("new help"))
        assertFalse(doc, doc.contains("old help"))
        assertTrue(doc, doc.contains("y number"))
    }

    fun `test a multi-line parameter list is shown on one line`() {
        myFixture.addFileToProject(
            "lib.libsonnet",
            """
            local d = import 'doc-util/main.libsonnet';
            {
              '#new':: d.fn('makes one', []),
              new(
                name,
                replicas=1,
              ):: {},
            }
            """.trimIndent(),
        )
        val doc = hoverInMain("local l = import 'lib.libsonnet'; l.ne<caret>w('a')")!!
        assertTrue(doc, doc.startsWith("fn new(name, replicas=1)"))
    }

    fun `test when the last of several definitions has no docs the previous documented one is used`() {
        myFixture.addFileToProject("gen.libsonnet", "local d = import 'doc-util/main.libsonnet'; { '#new':: d.fn('generated new', []), new(name):: {} }")
        myFixture.addFileToProject("ext.libsonnet", "{ new(name, replicas=1):: {} }")
        val doc = hoverInMain(
            "local gen = import 'gen.libsonnet'; local ext = import 'ext.libsonnet'; (gen + ext).ne<caret>w('a')",
        )!!
        assertTrue(doc, doc.contains("generated new"))
    }

    fun `test a computed help is left out but the parameters still show`() {
        myFixture.addFileToProject(
            "lib.libsonnet",
            """
            local d = import 'doc-util/main.libsonnet';
            local text = 'built elsewhere';
            {
              '#f':: d.fn(text, [d.arg('x', d.T.string)]),
              f(x):: x,
            }
            """.trimIndent(),
        )
        val doc = hoverInMain("local l = import 'lib.libsonnet'; l.<caret>f('a')")!!
        assertTrue(doc, doc.contains("fn f(x)"))
        assertTrue(doc, doc.contains("x string"))
        assertFalse(doc, doc.contains("built elsewhere"))
    }

    fun `test a field without an annotation gets no docsonnet hover`() {
        myFixture.addFileToProject("lib.libsonnet", "{ plain(x):: x }")
        assertNull(hoverInMain("local l = import 'lib.libsonnet'; l.pla<caret>in(1)"))
    }

    fun `test an annotation that documents nothing is not shown`() {
        myFixture.addFileToProject("lib.libsonnet", "local d = import 'doc-util/main.libsonnet'; { '#f':: d.fn(), f():: 1 }")
        assertNull(hoverInMain("local l = import 'lib.libsonnet'; l.<caret>f()"))
    }

    fun `test std hover is unaffected by the docsonnet provider`() {
        val doc = hoverInMain("std.jo<caret>in(',', [])")!!
        assertTrue(doc, doc.contains("std.join(sep, arr)"))
        assertFalse(doc, doc.contains("fn "))
    }
}
