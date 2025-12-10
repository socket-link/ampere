package link.socket.ampere.agents.environment.workspace

import kotlinx.serialization.Serializable

expect fun defaultWorkspace(): ExecutionWorkspace

/** Details about the workspace where the execution will take place */
@Serializable
data class ExecutionWorkspace(
    val baseDirectory: String,

)
