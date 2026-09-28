package link.socket.ampere.agents.environment.workspace

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId

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
 * Beyond the directory, a workspace can say what it is (AMPR-369): the
 * [repository] it is a checkout of, the [branch] it is on, and the [lease]
 * naming the one run that owns it. All three are optional, so a workspace built
 * from a directory alone is unchanged — it simply asserts nothing about its
 * identity or its owner.
 *
 * These properties make exclusive ownership *expressible*; they do not enforce
 * it. Nothing here hands out leases or refuses a second one. [holderAt],
 * [isAvailableTo] and [conflictsWith] are the pure checks an allocator would be
 * built from.
 *
 * @property baseDirectory Root of the workspace. Relative file paths supplied
 *   by an LLM are resolved against it and rejected when they escape it (see
 *   `resolveFileSafely` on the JVM/Android actuals of `write_code_file`).
 * @property repository The repository this workspace is a checkout of, as the
 *   caller identifies it (an `owner/name` slug or a remote URL). Opaque to
 *   Ampere and compared textually.
 * @property branch The branch the checkout is on.
 * @property lease The run that currently owns this workspace, or null when no
 *   ownership is claimed.
 */
@Serializable
data class ExecutionWorkspace(
    val baseDirectory: String,
    val repository: String? = null,
    val branch: String? = null,
    val lease: WorkspaceLease? = null,
) {

    /** The run holding this workspace at [now], or null when it is unleased or its lease has expired. */
    fun holderAt(now: Instant): RunId? =
        lease?.takeIf { it.isActiveAt(now) }?.holder

    /** Whether [runId] may use this workspace at [now]: nobody holds it, or [runId] already does. */
    fun isAvailableTo(runId: RunId, now: Instant): Boolean {
        val holder = holderAt(now)
        return holder == null || holder == runId
    }

    /**
     * Whether this workspace and [other] are one directory held by two
     * different runs at [now] — the state a lease exists to rule out.
     *
     * Directories are compared textually, so callers that need `/repo` and
     * `/repo/` (or a symlink and its target) to collide must canonicalize
     * [baseDirectory] before constructing the workspace.
     */
    fun conflictsWith(other: ExecutionWorkspace, now: Instant): Boolean {
        if (baseDirectory != other.baseDirectory) return false
        val holder = holderAt(now) ?: return false
        val otherHolder = other.holderAt(now) ?: return false
        return holder != otherHolder
    }
}

/**
 * A claim of exclusive ownership over an [ExecutionWorkspace] by one run.
 *
 * The lease travels with the workspace value, so whoever is handed a workspace
 * can see who owns it without consulting a registry. It is time-bounded so that
 * a run which dies without releasing does not strand the directory forever.
 *
 * @property holder The run that owns the workspace.
 * @property acquiredAt When the lease was taken.
 * @property expiresAt When the lease lapses on its own, or null for a lease
 *   held until it is explicitly released.
 */
@Serializable
data class WorkspaceLease(
    val holder: RunId,
    val acquiredAt: Instant,
    val expiresAt: Instant? = null,
) {

    /** Whether the lease is in force at [now]: taken, and not yet expired. */
    fun isActiveAt(now: Instant): Boolean =
        now >= acquiredAt && (expiresAt == null || now < expiresAt)
}
