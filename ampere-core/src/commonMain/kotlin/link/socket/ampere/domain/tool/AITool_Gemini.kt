@file:Suppress("ClassName")

package link.socket.ampere.domain.tool

sealed class AITool_Gemini(
    override val type: String,
    override val name: String,
) : AITool(type, name) {

    data object CodeExecution : AITool_Gemini(
        type = "",
        name = "",
    )

    data object UrlContext : AITool_Gemini(
        type = "",
        name = "",
    )

    data object WebSearch : AITool_Gemini(
        type = "",
        name = "",
    )
}
