package link.socket.ampere.lifecycle

import kotlin.jvm.JvmInline
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Principal
import link.socket.ampere.canon.CanonId

/** Identity of a [LifecycleDecision] within its [DecisionRegister], e.g. `L8`, `D17`. Opaque. */
@JvmInline
@Serializable
value class DecisionId(val value: String)

/**
 * One of the answers a [LifecycleDecision] considered.
 *
 * @property key Short handle the decision refers to the option by, e.g. `A`.
 *   Unique within one decision; see [LifecycleDecision.isCoherent].
 * @property summary What choosing it means, in prose.
 */
@Serializable
@SerialName("lifecycle.decision_option")
data class DecisionOption(
    val key: String,
    val summary: String,
)

/**
 * The record that a [LifecycleDecision] was locked.
 *
 * One value rather than two nullable fields on the decision, so a locked-at
 * with no locked-by — or the reverse — is unrepresentable.
 *
 * @property lockedAt When the lock was made. Bound once; never re-stamped.
 * @property lockedBy On whose authority. [Principal] is a carrier ahead of its
 *   semantics (AMPR-274), so today the only value is [Principal.Ambient] and a
 *   lock cannot yet name the person who made it. That is recorded here rather
 *   than worked around: a free-form name beside the principal would become the
 *   identity everyone actually reads.
 */
@Serializable
@SerialName("lifecycle.decision_lock")
data class DecisionLock(
    val lockedAt: Instant,
    val lockedBy: Principal,
)

/**
 * A question, the answers considered, and which one stands.
 *
 * The shipped shape this models is one line of the `L1`…`L12` / `D1`…`D10` /
 * `K1`…`K8` lists carried on epic descriptions: an identifier, the question,
 * the options, and the answer a person locked.
 *
 * Named `LifecycleDecision` because `Decision` is already spoken for three
 * times over, none of them this: `Escalation.Decision` is escalation handling,
 * `EmissionPayload.Decision` is a choice rendered to a user, and
 * `RoutingDecision` is a relay's pick of a model. None carries options, a
 * default, or a lock.
 *
 * ## Proposed, chosen, locked
 *
 * A decision is *proposed* with a [defaultOption] and nothing chosen. It may
 * be *chosen* — [chosenOption] set — and re-chosen freely. Once *locked* it no
 * longer changes; [choose] and [lock] both refuse. What a locked decision
 * decided is [effectiveOption], whether or not anyone chose.
 *
 * **The default is explicit so that silence is an answer.** A decision locked
 * with nothing chosen resolves to [defaultOption], and [chosenOption] stays
 * null so the record still says nobody chose — the default took effect. An
 * implicit default would make that case indistinguishable from an unfinished
 * register.
 *
 * **No `init` validation**, for the reason `CanonWorkGraph` gives: a decision
 * whose default names an option that is not there must be constructible and
 * decodable, so it can be recorded and then caught. [isCoherent] exposes the
 * rules; [choose] and [lock] are the write-side paths that enforce them.
 *
 * @property id Identity within the register.
 * @property question What is being decided.
 * @property options The answers considered, in the order they were proposed.
 * @property defaultOption The [DecisionOption.key] that stands when nothing is
 *   chosen. Required, never inferred from position.
 * @property chosenOption The [DecisionOption.key] somebody picked, or null.
 * @property lock Present once the decision is locked.
 */
@Serializable
@SerialName("lifecycle.decision")
data class LifecycleDecision(
    val id: DecisionId,
    val question: String,
    val options: List<DecisionOption>,
    val defaultOption: String,
    val chosenOption: String? = null,
    val lock: DecisionLock? = null,
) {

    val isLocked: Boolean
        get() = lock != null

    /** The option that stands: the chosen one, or the default when nothing was chosen. */
    val effectiveOption: String
        get() = chosenOption ?: defaultOption

    /**
     * Whether option keys are unique and both [defaultOption] and
     * [chosenOption] name one of them.
     */
    val isCoherent: Boolean
        get() {
            val keys = options.map { it.key }
            return keys.toSet().size == keys.size &&
                defaultOption in keys &&
                (chosenOption == null || chosenOption in keys)
        }

    /** Pick [optionKey]. Fails once locked, or when no option has that key. */
    fun choose(optionKey: String): Result<LifecycleDecision> = when {
        isLocked ->
            Result.failure(IllegalStateException("decision ${id.value} is locked and cannot be re-chosen"))

        options.none { it.key == optionKey } ->
            Result.failure(IllegalArgumentException("decision ${id.value} has no option '$optionKey'"))

        else -> Result.success(copy(chosenOption = optionKey))
    }

    /**
     * Lock the decision as it stands. Fails when already locked — a lock is
     * never re-stamped — or when the decision is not [isCoherent], since
     * locking it would make an answer nobody can resolve permanent.
     */
    fun lock(at: Instant, by: Principal): Result<LifecycleDecision> = when {
        isLocked ->
            Result.failure(IllegalStateException("decision ${id.value} is already locked"))

        !isCoherent ->
            Result.failure(IllegalStateException("decision ${id.value} names an option it does not have"))

        else -> Result.success(copy(lock = DecisionLock(lockedAt = at, lockedBy = by)))
    }
}

/**
 * The decisions belonging to one unit of work, in order.
 *
 * Order is the order they were proposed and is preserved on the wire — `L3`
 * is read after `L2` because it was written after it, and later decisions
 * routinely amend earlier ones.
 *
 * Not a canon entity, for the reason [ReconFinding] gives. No provider ships a
 * decision register; the shipped instances are prose on an epic description.
 * A binding that reads or writes one through a provider is separate work.
 *
 * @property subject The unit of work the register belongs to. A same-Link
 *   [CanonId] under the cross-reference contract in
 *   `docs/concepts/domain-canon.md`.
 * @property decisions The decisions, in order.
 */
@Serializable
@SerialName("lifecycle.decision_register")
data class DecisionRegister(
    val subject: CanonId,
    val decisions: List<LifecycleDecision> = emptyList(),
) {

    /**
     * Whether every decision is locked. **False for an empty register**: a
     * register nothing has been proposed into yet must not read as settled to
     * whatever is waiting on it.
     */
    val isLocked: Boolean
        get() = decisions.isNotEmpty() && decisions.all { it.isLocked }

    /** The decisions still open, in register order. */
    val unlocked: List<LifecycleDecision>
        get() = decisions.filterNot { it.isLocked }

    /** Whether every decision is [LifecycleDecision.isCoherent] and no two share an id. */
    val isCoherent: Boolean
        get() = decisions.all { it.isCoherent } &&
            decisions.map { it.id }.toSet().size == decisions.size

    /** The decision with [id], or null. The first, should a register carry a duplicate. */
    operator fun get(id: DecisionId): LifecycleDecision? = decisions.firstOrNull { it.id == id }

    /**
     * Lock every decision still open, in one act. Decisions already locked
     * keep the lock they have. All-or-nothing: if any open decision refuses
     * (see [LifecycleDecision.lock]) or two decisions share an id, nothing is
     * locked and the failure names the offender.
     */
    fun lock(at: Instant, by: Principal): Result<DecisionRegister> {
        val duplicate = decisions.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            return Result.failure(IllegalStateException("register carries decision ${duplicate.key.value} twice"))
        }

        val locked = decisions.map { decision ->
            if (decision.isLocked) {
                decision
            } else {
                decision.lock(at, by).getOrElse { return Result.failure(it) }
            }
        }
        return Result.success(copy(decisions = locked))
    }
}
