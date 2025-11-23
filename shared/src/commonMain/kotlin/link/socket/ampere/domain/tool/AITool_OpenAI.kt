@file:Suppress("ClassName")

package link.socket.ampere.domain.tool

sealed class AITool_OpenAI(
    override val type: String,
    override val name: String,
) : AITool(type, name) {

    data object CodeExecution : AITool_OpenAI(
        type = "",
        name = "",
    )

    data object FileSearch : AITool_OpenAI(
        type = "",
        name = "",
    )

    data object ImageGeneration : AITool_OpenAI(
        type = "",
        name = "",
    )

    data object MCP : AITool_OpenAI(
        type = "",
        name = "",
    )

    data object WebSearch : AITool_OpenAI(
        type = "",
        name = "",
    )
}
