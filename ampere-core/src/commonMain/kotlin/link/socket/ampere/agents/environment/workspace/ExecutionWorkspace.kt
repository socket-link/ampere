package link.socket.ampere.agents.environment.workspace

import kotlinx.serialization.Serializable

/**
 * The directory an agent's file operations are confined to.
 *
 * There is deliberately no default workspace (AMPR-300). The former
 * `defaultWorkspace()` resolved to one directory shared by every agent in the
 * process (`~/.ampere/Workspaces/Ampere`), which voided any per-dispatch
 * sandbox before a single tool ran. Every agent-creating path —
 * [link.socket.ampere.agents.definition.AgentFactory],
 * [link.socket.ampere.agents.definition.SparkAgentFactory], and the Arc
 * runtime's project directory — now takes the workspace as an explicit,
 * required argument, and the code-file tools refuse to dispatch when a request
 * reaches them without one rather than falling back to the process working
 * directory.
 *
 * @property baseDirectory Root of the workspace. Relative file paths supplied
 *   by an LLM are resolved against it and rejected when they escape it (see
 *   `resolveFileSafely` on the JVM/Android actuals of `write_code_file`).
 */
@Serializable
data class ExecutionWorkspace(
    val baseDirectory: String,
)
