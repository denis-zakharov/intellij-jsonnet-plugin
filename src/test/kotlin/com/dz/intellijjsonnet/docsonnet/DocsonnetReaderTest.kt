package com.dz.intellijjsonnet.docsonnet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DocsonnetReaderTest {

    @Test
    fun `k8s generator quoting is unwrapped`() {
        assertEquals("Annotations is a map.", DocsonnetReader.unquoteGeneratedHelp("\"Annotations is a map.\""))
        assertEquals(
            "A map.\n\n**Note:** This function appends passed data to existing values",
            DocsonnetReader.unquoteGeneratedHelp("\"A map.\"\n\n**Note:** This function appends passed data to existing values"),
        )
    }

    @Test
    fun `escapes left inside the quoted text are decoded`() {
        assertEquals("One.\n\nTwo.", DocsonnetReader.unquoteGeneratedHelp("\"One.\\n\\nTwo.\""))
        assertEquals("the \"default\" namespace", DocsonnetReader.unquoteGeneratedHelp("\"the \\\"default\\\" namespace\""))
    }

    @Test
    fun `help that merely starts with a quoted word is left alone`() {
        assertEquals("\"quoted\" is a word", DocsonnetReader.unquoteGeneratedHelp("\"quoted\" is a word"))
        assertEquals("plain `code` help", DocsonnetReader.unquoteGeneratedHelp("plain `code` help"))
        assertEquals("\"never closed", DocsonnetReader.unquoteGeneratedHelp("\"never closed"))
    }
}
