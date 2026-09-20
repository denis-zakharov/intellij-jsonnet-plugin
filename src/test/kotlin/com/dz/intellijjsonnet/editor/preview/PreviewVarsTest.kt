package com.dz.intellijjsonnet.editor.preview

import com.dz.intellijjsonnet.engine.JsonnetEngine.VarValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreviewVarsTest {

    @Test
    fun `name=value is a string var, name colon-equals code is a code var`() {
        val vars = PreviewVars.parse("env=prod\nreplicas:=3\ntags := ['a', 'b']")
        assertEquals(VarValue("prod", isCode = false), vars["env"])
        assertEquals(VarValue("3", isCode = true), vars["replicas"])
        assertEquals(VarValue("['a', 'b']", isCode = true), vars["tags"])
    }

    @Test
    fun `only the first equals sign splits, so string values may contain equals`() {
        assertEquals(VarValue("a=b=c"), PreviewVars.parse("q=a=b=c")["q"])
    }

    @Test
    fun `blank lines, comments, and malformed lines are skipped and later lines win`() {
        val vars = PreviewVars.parse("\n# note\nnoequals\n=novalue\n:=x\nk=1\nk=2\n")
        assertEquals(mapOf("k" to VarValue("2")), vars)
    }
}
