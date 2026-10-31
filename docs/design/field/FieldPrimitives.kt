// Design artifact — NOT compiled. The reviewed sketch of the Field primitives behind
// F-register row F6 (AMPR-328), kept beside the ADR as the shape the verdict was taken
// on. The shipped package is ampere-core/src/commonMain/kotlin/link/socket/ampere/field/
// and is authoritative; the KDoc there carries each decision's rationale.
//
// Differences between this sketch and the shipped package, all resolved toward the
// package during AMPR-415:
//   - `Provenance.createdBy` is a `String` (an `Event.eventId`), not the `EventId`
//     alias: the alias lives in `agents.domain.event`, which the leaf cannot import.
//   - `Action.executor` is a `String?` (an `Executor.id`): `Executor` lives in
//     `agents.execution`, which the leaf cannot import, and stays the sealed type it is —
//     F17 as locked dropped the `ExecutorRef` rename. Since AMPR-443 the live path carries
//     that id apart from the deciding agent (`ExecutionContext.actorId` / `executorId`, the
//     tool-event pair's `executorId`, the outcome row's `actor_id`), which is what a fold
//     filling this field will read.
//   - `Action` has its own `ActionId`; it is a record about primitives, not a
//     `Primitive`, so it cannot be a `Ref` target.
//   - `Environment` is an open marker interface, not sealed: its implementations are
//     the canon entities (F7), which live in another package.
//   - No `@Serializable` anywhere: wire shape is each store's decision.
//
// Changed after AMPR-415, toward the package:
//   - `Belief` carries a required `kind: BeliefKind` (AMPR-448, F21).
//   - `Action.reversibility` and `Action.cost` are nullable (`null` = unknown), and `Action`
//     carries `result: ActionResult?`, set only on a `TAKEN` Action (AMPR-448, F20).
//   - `Rule.Window`, a recurring time-of-day window over tool-id labels, which the Breaker
//     evaluates against the door's clock in the rule's own zone (AMPR-511).

package link.socket.ampere.field

// ---- Primitives: three branches, identity by Ref ---------------------------------

sealed interface Primitive {
    val id: Ref<Primitive>
}

/** Phantom-typed handle. `out` so Ref<Subject.Agent> is a Ref<Subject> is a Ref<Primitive>. */
@JvmInline
value class Ref<out T : Primitive>(val value: String)

sealed interface Subject : Primitive {
    override val id: Ref<Subject>
    val name: String
    val provenance: Provenance

    data class Agent(override val id: Ref<Agent>, override val name: String, override val provenance: Provenance) : Subject
    data class Human(override val id: Ref<Human>, override val name: String, override val provenance: Provenance) : Subject
    data class Group(override val id: Ref<Group>, override val name: String, override val provenance: Provenance) : Subject
    // Membership is Relationship(MemberOf), never a member list.
}

data class Goal(
    override val id: Ref<Goal>,
    val description: String,
    val parent: Ref<Goal>? = null,
    val provenance: Provenance,
) : Primitive

/** Marker. The canon is the Environment branch (F7); nothing re-declared here. */
interface Environment : Primitive

// ---- Provenance: the envelope, not the type (F2) ---------------------------------

data class Provenance(val createdBy: String, val createdAt: Instant)

// ---- Relationship: structure as edges ---------------------------------------------

data class Relationship<out From : Primitive, out To : Primitive>(
    val from: Ref<From>,
    val to: Ref<To>,
    val kind: RelationshipKind,
    val provenance: Provenance,
)

sealed interface RelationshipKind {
    data object Mirrors : RelationshipKind   // tracker's work item ↔ the agent's Goal (F8)
    data object MemberOf : RelationshipKind  // agent ∈ team, human ∈ room (F30)
}

// ---- Belief: holder, subject, confidence, sensitivity, decay; Settled/Unsettled ----

sealed interface Belief {
    val holder: Ref<Subject>
    val subject: Ref<Primitive>
    val content: String
    val sensitivity: Sensitivity
    val decay: Decay
    val provenance: Provenance
    val confidence: Double?            // already-mapped (F10); source stays on Confidence

    data class Settled(
        override val holder: Ref<Subject>,
        override val subject: Ref<Primitive>,
        override val content: String,
        override val confidence: Double,
        override val sensitivity: Sensitivity,
        override val decay: Decay = Decay.None,
        override val provenance: Provenance,
    ) : Belief

    data class Unsettled(
        override val holder: Ref<Subject>,
        override val subject: Ref<Primitive>,
        override val content: String,
        override val sensitivity: Sensitivity,
        override val decay: Decay = Decay.None,
        override val provenance: Provenance,
    ) : Belief {
        override val confidence: Double? get() = null
    }
}

enum class Sensitivity { PUBLIC, PRIVATE, PROTECTED }   // F12

sealed interface Decay {
    data object None : Decay
    data class HalfLife(val halfLife: Duration) : Decay
    data class ExpiresAt(val at: Instant) : Decay
}

// ---- Constraint: a Rule bound to a subject; predicate derived at probe time --------

data class Constraint(
    val subject: Ref<Primitive>,
    val rule: Rule,
    val hardness: Hardness,
    val origin: Ref<Subject>,
    val provenance: Provenance,
)

enum class Hardness { HARD, SOFT }

sealed interface Rule {
    data class Requirements(val requireTests: Boolean = true, val requireLinting: Boolean = true, val allowBreakingChanges: Boolean = false) : Rule
    sealed interface Quota : Rule {
        data class Time(val limit: Duration) : Quota
        data class Count(val limit: Int, val of: String) : Quota
        data class Tokens(val limit: Long) : Quota
    }
    data class Transition(val allowed: Map<String, Set<String>>) : Rule
    data class AccessPolicy(val required: Set<String>) : Rule
    data class Dependency(val dependsOn: Set<Ref<Goal>>) : Rule
    data class Deadline(val due: Instant) : Rule
    data class Cadence(val every: Duration, val anchor: Instant? = null) : Rule
    data class Window(val from: LocalTime, val until: LocalTime, val timezone: String, val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), val appliesTo: Set<String> = emptySet()) : Rule
}

// ---- Action: Proposed → Admitted → Taken; actor ≠ executor (F17) -------------------

@JvmInline
value class ActionId(val value: String)

data class Action(
    val id: ActionId,
    val actor: Ref<Subject>,
    val executor: String? = null,
    val goal: Ref<Goal>? = null,
    val description: String,
    val state: ActionState,
    val reversibility: Reversibility,
    val cost: Cost,
    val prediction: Prediction? = null,
    val provenance: Provenance,
)

enum class ActionState { PROPOSED, ADMITTED, TAKEN }
enum class Reversibility { REVERSIBLE, COSTLY, IRREVERSIBLE }
data class Cost(val watts: Double)
data class Prediction(val outcome: String, val confidence: Double)

// ---- Signals: names only (F27); arithmetic is F28 ----------------------------------

@JvmInline value class Potential(val value: Double)
@JvmInline value class Resistance(val value: Double)
// priority / urgency / trust: derived at read time, never stored.
