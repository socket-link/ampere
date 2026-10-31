package link.socket.ampere.agents.execution.dispatch

import kotlinx.serialization.Serializable

/**
 * Why the Switchboard did not dispatch a candidate (AMPR-308).
 *
 * One member per rule of the ready-queue evaluation, so every refusal names the rule
 * that refused it rather than a generic "not ready". The order is the order the rules
 * are checked in; a candidate is refused for the first one it fails.
 */
@Serializable
enum class RefusalReason {

    /** The fresh read no longer carries the wave's label. */
    NOT_IN_WAVE,

    /**
     * The ticket carries a gate label. A gated ticket is never dispatched, and this is
     * checked before the state so a gate always reads as a gate.
     */
    GATED,

    /** The fresh read shows the ticket outside the queued state — someone took it. */
    NOT_QUEUED,

    /** A blocking ticket is still open. */
    BLOCKED,

    /**
     * A blocking ticket labeled `research` is not both Done and carrying a human
     * verdict comment. The rule stricter than operator practice, ratified 2026-08-23:
     * a recon ticket closed with no verdict recorded is exactly what it guards.
     */
    RESEARCH_BLOCKER_UNVERDICTED,

    /**
     * The ticket's `ampere-scope` block declares no path in the repository this
     * Switchboard dispatches into.
     */
    SCOPE_OUTSIDE_REPOSITORY,

    /**
     * The ticket has no readable `ampere-scope` block, and something else is in
     * flight. Unknown scope overlaps everything, so it can only be dispatched alone.
     */
    SCOPE_UNKNOWN,

    /** The ticket's scope overlaps an in-flight ticket's, or an in-flight ticket's scope is unknown. */
    SCOPE_OVERLAP,

    /** A read the evaluation needed failed, so readiness could not be established. */
    UNREADABLE,
}

/** How an agent run ended, as the Switchboard observed it. */
@Serializable
enum class AgentStop {

    /** The agent exited on its own. */
    EXITED,

    /**
     * The agent produced no output for the silence limit — the one automated kill
     * rule (operator answer 9 on AMPR-288) — and its process group was terminated.
     */
    SILENCED,

    /** The supervisor shut down while the agent ran, and terminated its group. */
    SHUTDOWN,
}
