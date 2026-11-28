package com.xfiles.fily

/**
 * A llama.cpp grammar (GBNF) for Fily's answers: exactly the three compact shapes it was trained to
 * produce, and only the tools offered in this prompt. The model can then no longer invent a tool or
 * emit broken JSON; the app still validates every argument before acting.
 */
internal object FilyGrammar {
    fun forTools(tools: List<String>): String {
        val kinds = buildList {
            if (tools.isNotEmpty()) add("tool")
            add("reply")
            add("clarify")
        }
        val names = tools.joinToString(" | ") { "\"\\\"$it\\\"\"" }
        return buildString {
            appendLine("""root ::= "{\"kind\":" ( ${kinds.joinToString(" | ")} ) "}"""")
            if (tools.isNotEmpty()) {
                appendLine("""tool ::= "\"tool\",\"name\":" name ",\"arguments\":" object""")
                appendLine("name ::= $names")
            }
            appendLine("""reply ::= "\"reply\",\"text\":" string ",\"citations\":" strings""")
            appendLine("""clarify ::= "\"clarify\",\"text\":" string""")
            appendLine("""object ::= "{" ( pair ( "," pair )* )? "}"""")
            appendLine("""pair ::= string ":" value""")
            appendLine("""value ::= object | array | string | number | "true" | "false" | "null"""")
            appendLine("""array ::= "[" ( value ( "," value )* )? "]"""")
            appendLine("""strings ::= "[" ( string ( "," string )* )? "]"""")
            appendLine("""string ::= "\"" ( [^"\\\x00-\x1F] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) )* "\""""")
            appendLine("""number ::= "-"? [0-9]+ ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )?""")
        }
    }
}
