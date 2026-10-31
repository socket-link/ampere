package link.socket.ampere.field

import kotlin.time.Duration
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime

/**
 * A [Rule] bound to a subject, with a hardness and an origin.
 *
 * A Constraint is data all the way down. The predicate that decides whether a proposed
 * [Action] honours it is *derived from the Rule at probe time* by the Breaker (W3,
 * F13/F16) and is never stored — not as a lambda, not as a serialized expression. A
 * stored predicate cannot be inspected, cannot be diffed between two folds and cannot
 * be rendered in a glass-brain view; a stored Rule can be all three.
 *
 * @property subject What the rule binds: an agent, a goal, an environment entity.
 * @property rule The rule; see [Rule].
 * @property hardness Whether a breach trips or merely resists; see [Hardness].
 * @property origin On whose authority the rule binds — the Principal, a team policy's
 *   Group, the Subject whose manifest declared it. Distinct from [provenance], which is
 *   the *event* that bound it.
 */
data class Constraint(
    val subject: Ref<Primitive>,
    val rule: Rule,
    val hardness: Hardness,
    val origin: Ref<Subject>,
    val provenance: Provenance,
)

/**
 * Whether breaching a [Constraint] trips the Breaker or adds resistance.
 *
 * Hard rules — permission gates, status transitions, quotas, deadlines — are decided
 * deterministically and are never model-evaluated (F15 as confirmed by AMPR-381 J7). A
 * judgment reaching the Breaker is evidence: it can add resistance or trip, and it can
 * never clear a Hard trip.
 */
enum class Hardness {

    /** A breach trips: the Action is not admitted. */
    HARD,

    /** A breach resists: the Action's [Resistance] rises and ranking decides. */
    SOFT,
}

/**
 * The closed set of things a [Constraint] can require.
 *
 * Each case is the data form of a rule the codebase already enforces somewhere, or
 * declares and never reads (F14). The predicate for each is derived at probe time;
 * none of these types has a function member, and none should gain one.
 */
sealed interface Rule {

    /**
     * What an execution must satisfy to count as successful — the three Boolean
     * members of the retired `ExecutionConstraints`, which were declared on every
     * request and read by nothing (F14).
     *
     * **Checked by verification, not by the Breaker.** These are post-conditions: whether
     * the tests pass or the change breaks an API is a fact about work that has finished,
     * and the Breaker admits a step before it starts. A Breaker handed one reports it as a
     * rule it cannot evaluate, which fails the admission (AMPR-445). The repository's
     * verification manifest is where a requirement is run.
     */
    data class Requirements(
        val requireTests: Boolean = true,
        val requireLinting: Boolean = true,
        val allowBreakingChanges: Boolean = false,
    ) : Rule

    /**
     * A ceiling on a consumed resource. Three resources, each its own case, because a
     * duration, a count and a token total are not interchangeable numbers.
     *
     * A quota is breached once the spend *reaches* its limit, and the spend is read from
     * the persisted record, never from a counter (AMPR-445). A hosted run's `RunQuotas`
     * are the first source of these rules.
     */
    sealed interface Quota : Rule {

        /**
         * Wall-clock budget; was `ExecutionConstraints.timeoutMinutes` (F14). Measured
         * from the run's first recorded event to the Field's clock (F29).
         */
        data class Time(val limit: Duration) : Quota

        /**
         * A count of things; was `ExecutionConstraints.maxFilesChanged` (F14).
         *
         * @property of What is counted, as a label. The Breaker counts `steps` — a run's
         *   `PlanStepCompleted` events — and nothing else yet; any other label is a rule
         *   it cannot evaluate.
         */
        data class Count(val limit: Int, val of: String) : Quota

        /**
         * A token budget. The first quota with a data source: `ProviderCallCompleted.usage`
         * is on the persisted path, keyed by agent, workflow and phase (F14 amendment).
         * The spend is `inputTokens + outputTokens` summed over the run's calls.
         */
        data class Tokens(val limit: Long) : Quota
    }

    /**
     * Which state changes are allowed. Wraps `TicketStatus.validTransitions()` as a Hard
     * sequencing rule (F14 amendment); states are labels because this package cannot
     * name `TicketStatus`. `TicketStatus.asTransitionRule()` builds it, and
     * `Breaker.admitTransition` is its one evaluation (AMPR-445).
     *
     * @property allowed For each source state, the set of states it may move to. A
     *   state absent from the map has no allowed transitions.
     */
    data class Transition(val allowed: Map<String, Set<String>>) : Rule

    /**
     * Who may do what. `PlugPermissionGate` is the first Hard Rule of this kind (F15),
     * unchanged internally; its `GateResult` maps onto `Admission`. The permissions are
     * the gate's own identifiers, carried as labels.
     */
    data class AccessPolicy(val required: Set<String>) : Rule

    /**
     * Something must be done first. `WorkItem.dependsOn` becomes this (F8): a Goal's
     * dependencies are Constraints on the Goal, not members of it.
     */
    data class Dependency(val dependsOn: Set<Ref<Goal>>) : Rule

    /** Done by then. Evaluated against the Field's clock, not wall-clock (F29). */
    data class Deadline(val due: Instant) : Rule

    /**
     * Done every so often. Evaluated against the Field's clock (F29).
     *
     * @property every The period.
     * @property anchor A point the period is counted from; `null` means from the
     *   constraint's [Provenance.createdAt].
     */
    data class Cadence(val every: Duration, val anchor: Instant? = null) : Rule

    /**
     * Not between these hours. Evaluated against the Breaker's clock in [timezone] (F29) —
     * the door's clock, read in the rule's own zone, never the device's. The predicate is
     * derived at probe time like every other rule's: "inside" is decided from these fields
     * and the clock when a step is admitted, and nothing here computes it.
     *
     * What the rule applies to is carried as labels, as [AccessPolicy.required] carries
     * permissions, because this package cannot name a tool. A consumer that wants thinking
     * and reading to go on while acting stops puts its acting tools' ids in [appliesTo];
     * which tools act is the consumer's knowledge, not Ampere's.
     *
     * @property from When the window opens, inclusive.
     * @property until When it closes, exclusive. One earlier than [from], or equal to it,
     *   wraps midnight: the window runs from [from] on one day to [until] on the next.
     * @property timezone The IANA zone [from] and [until] are read in. A zone the platform
     *   cannot resolve is a rule the Breaker cannot evaluate, never one that holds.
     * @property days The days the window opens on — the day [from] falls on, so a window
     *   from 22:00 to 07:00 on Friday covers Saturday's early morning too.
     * @property appliesTo The tool ids the window stops, as labels. Empty means every
     *   step, including one that nominates no tool; a step with no tool is never inside a
     *   non-empty selector.
     */
    data class Window(
        val from: LocalTime,
        val until: LocalTime,
        val timezone: String,
        val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
        val appliesTo: Set<String> = emptySet(),
    ) : Rule
}
