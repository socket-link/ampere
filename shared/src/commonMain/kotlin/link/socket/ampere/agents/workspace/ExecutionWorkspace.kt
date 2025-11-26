package link.socket.ampere.agents.workspace

import kotlinx.serialization.Serializable

/** Details about the workspace where the execution will take place */
@Serializable
data class ExecutionWorkspace(
    val baseDirectory: String,
)
