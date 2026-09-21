package link.socket.ampere.agents.execution.process

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Where to send "stop" for a spawned subprocess, and everything it spawned in turn.
 *
 * D4's rule — no delegation without a cancellation address — applies to local
 * subprocesses as much as to remote jobs: a child survives its parent's SIGKILL
 * (reparented to PID 1), so a restarted supervisor can only reap what it recorded.
 * This value is captured at spawn and is small and serializable on purpose, so the
 * owner of the recovery path (e.g. the CLI supervisor's claim journal) can persist
 * it next to the dispatch it belongs to and terminate the group after a restart.
 *
 * @property leaderPid PID of the process the caller asked to spawn.
 * @property processGroupId The group the leader was placed in at spawn; signalling
 *   `-processGroupId` reaches the leader and every descendant that did not leave the
 *   group. Null on hosts without POSIX process groups (Windows), where termination
 *   falls back to the leader's live process tree.
 * @property leaderStartedAt The leader's start time as reported by the OS. Used to
 *   refuse termination when the recorded PID has since been reused by an unrelated
 *   process. Null if the OS did not report it.
 */
@Serializable
data class CancellationAddress(
    val leaderPid: Long,
    val processGroupId: Long?,
    val leaderStartedAt: Instant?,
)

/** What happened when a [CancellationAddress] was terminated. */
@Serializable
enum class TerminationOutcome {
    /** Nothing at the address was running; no signal was sent. */
    ALREADY_GONE,

    /** The group exited within the grace period after SIGTERM. */
    TERMINATED,

    /** The group ignored SIGTERM for the whole grace period and was SIGKILLed. */
    KILLED,

    /** Something at the address was still running even after SIGKILL. */
    SURVIVED,

    /**
     * The recorded leader PID now belongs to a different process (its start time
     * differs), so nothing was signalled.
     */
    REFUSED_ADDRESS_REUSED,
}
