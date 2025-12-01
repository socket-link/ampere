@file:Suppress("ClassName")

package link.socket.ampere.domain.tool

sealed class AITool_Claude(
    override val type: String,
    override val name: String,
) : AITool(type, name) {

    data object Bash : AITool_Claude(
        type = "bash_20250124",
        name = "bash",
    )

    /**
     * Currently in beta, so it also requires this header:
     * "anthropic-beta": "code-execution-2025-05-22"
     */
    data object CodeExecution : AITool_Claude(
        type = "code_execution_20250522",
        name = "code_execution",
    )

    sealed class TextEditor(
        override val name: String,
        override val type: String,
    ) : AITool_Claude(name, type) {

        data object _4 : TextEditor(
            type = "text_editor_20250728",
            name = "str_replace_based_edit_tool",
        )

        data object _3_7 : TextEditor(
            type = "text_editor_20250124",
            name = "str_replace_based_edit_tool",
        )
    }

    data object WebSearch : AITool_Claude(
        type = "web_search_20250305",
        name = "web_search",
    )
}
