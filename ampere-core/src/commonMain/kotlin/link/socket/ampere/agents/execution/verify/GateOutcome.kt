package link.socket.ampere.agents.execution.verify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What one gate reported. The set is closed (schema v1, §6).
 *
 * Who owns the next move is the whole reason there are four and not two:
 * [PASSED] nobody, [FAILED] the agent, [INFRASTRUCTURE] the host, [NOT_EXECUTED]
 * the operator.
 */
@Serializable
enum class GateOutcome {

    /** Exit code 0. */
    @SerialName("passed")
    PASSED,

    /**
     * A non-zero exit with every declared precondition satisfied and no
     * infrastructure signal: the code under test is wrong. Also the answer when the
     * evidence is unclear — a misclassified failure wastes a repair attempt, while a
     * misclassified pass would surface a bad merge request.
     */
    @SerialName("failed")
    FAILED,

    /**
     * The gate produced no verdict about the code: it timed out, a `${env.VAR}` it
     * names is unset, its command could not be started, a precondition it depends on
     * broke while it ran, or its failure did not reproduce. Escalates about the host
     * and never spends the agent's repair budget.
     */
    @SerialName("infrastructure")
    INFRASTRUCTURE,

    /**
     * A precondition was absent — a hard one (the whole run was refused) or a soft
     * one this gate needs — so it never ran. Absent evidence with a named blocker:
     * never a pass, never the agent's fault.
     */
    @SerialName("not_executed")
    NOT_EXECUTED,
}

/**
 * What a whole verification run says about the dispatch, and so what happens next.
 *
 * Exactly one applies, in this order of precedence: a run that could not start says
 * nothing about anything; a run with any host failure has a verdict nobody can trust
 * yet; an agent failure is only charged when the host was sound; and a run is green
 * only when every gate passed.
 */
@Serializable
enum class VerificationVerdict {

    /** A hard precondition was absent, so no gate ran. Escalates about the host. */
    @SerialName("refused")
    REFUSED,

    /** The supervisor shut down while gates ran. Escalated as an interrupted run. */
    @SerialName("interrupted")
    INTERRUPTED,

    /** At least one gate was infrastructure. Escalates about the host; no repair attempt is spent. */
    @SerialName("host_failure")
    HOST_FAILURE,

    /** At least one gate failed on a sound host. Feeds back to the agent until the budget is spent. */
    @SerialName("agent_failure")
    AGENT_FAILURE,

    /**
     * Nothing failed, but a soft precondition kept a gate from running. Not green:
     * escalates to the operator naming the blocker.
     */
    @SerialName("incomplete")
    INCOMPLETE,

    /** Every gate ran here, honestly, and passed. The only verdict that surfaces a merge request. */
    @SerialName("green")
    GREEN,
}

/**
 * One gate's result as the dispatch journal records it: the gate id, its exit code,
 * how long it took and its outcome class (AMPR-309, Expected Outcome 2).
 *
 * Carries nothing a command printed. The log stays on the supervisor host; this is
 * the record of *what was decided*, which is the part recovery and audit need.
 *
 * @property attempt Which verification of the dispatch this was: 0 after the agent's
 *   first run, n after its n-th repair.
 * @property step The step that decided the outcome, for a `steps:` gate.
 * @property exitCode Null when the gate never produced one — not executed, timed out,
 *   or never started.
 * @property reason Why the gate got its outcome, composed from fixed text and
 *   manifest identifiers only.
 */
@Serializable
data class GateRecord(
    val gateId: String,
    val outcome: GateOutcome,
    val attempt: Int,
    val durationMillis: Long,
    val exitCode: Int? = null,
    val step: String? = null,
    val reason: String? = null,
)
