package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable

/** Platform-agnostic request for executing a tool */
@Serializable
data class ExecutionRequest <Context : ExecutionContext>(
    /** Additional context to help the executor understand how to execute the task */
    val context: Context,
    /** Constraints that bound the execution */
    val constraints: ExecutionConstraints,
)
