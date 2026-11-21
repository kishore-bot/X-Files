package com.xfiles.fily

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseParserTest {
    @Test
    fun oldAppNameIsSpokenAsXfiles() {
        val reply = parseModelResponse("""{"kind":"reply","text":"EzFiles does not execute document macros.","citations":[]}""")
        assertEquals("Xfiles does not execute document macros.", reply.text)
    }

    @Test
    fun toolArgumentsKeepTheOldName() {
        val tool = parseModelResponse("""{"kind":"tool","name":"searchLibrary","arguments":{"query":"ezfiles notes"}}""")
        assertEquals("ezfiles notes", tool.toolArgs?.get("query")?.toString()?.trim('"'))
    }
}
