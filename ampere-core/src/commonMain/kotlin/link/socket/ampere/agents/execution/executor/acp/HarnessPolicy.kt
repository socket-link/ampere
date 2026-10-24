package link.socket.ampere.agents.execution.executor.acp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a consumer decides about a harness beyond the spark's file access scope (AMPR-492).
 *
 * The scope says which paths may be read and written; it has nothing to say about a shell
 * command, a hung turn or an agent that ignores a cancel. Those are here, with defaults that
 * err toward refusing.
 *
 * @property shell Whether the harness may run shell commands (an ACP tool call of kind
 *   `execute`). [ShellAccess.Deny] by default: a command's effects are not paths the fence
 *   can check before they happen.
 * @property cancelGrace How long a cancelled turn waits for the agent to answer
 *   `session/cancel` with `cancelled` before the connection is closed and the process ended.
 * @property promptTimeout How long one turn may run before it is cancelled and reported as a
 *   timeout. Null — the default — is no limit: a harness working a real step may need
 *   minutes, and a run's own wall-clock quota is the Breaker's to enforce.
 * @property startTimeout How long a freshly spawned agent gets to answer `initialize`.
 */
data class HarnessPolicy(
    val shell: ShellAccess = ShellAccess.Deny,
    val cancelGrace: Duration = 5.seconds,
    val promptTimeout: Duration? = null,
    val startTimeout: Duration = 30.seconds,
) {

    enum class ShellAccess {
        Deny,
        Allow,
    }
}
