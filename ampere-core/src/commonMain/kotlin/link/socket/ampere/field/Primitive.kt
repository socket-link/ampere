package link.socket.ampere.field

import kotlin.jvm.JvmInline

/*
 * Package `link.socket.ampere.field` — the Field primitives (AMPR-415, F-register rows F6 and F27).
 *
 * The Field is Ampere's world state at any instant: a deterministic fold of persisted
 * Events into stored state. This package holds the *types* that fold produces and
 * nothing else — no services, no persistence, no behaviour. The fold itself
 * (`FieldService`) follows once the W2 stores exist.
 *
 * Three rules every file in this package honours:
 *
 * 1. **Leaf module.** `field` imports nothing from `agents.domain`, `tickets`, `meetings`
 *    or `messages`. Those packages depend on `field`; never the reverse. A grep-gated
 *    boundary test (`FieldBoundaryTest`, jvmTest) fails the build if an import crosses
 *    the line. The same rule is why `Provenance.createdBy` is a `String` and not the
 *    `EventId` typealias: the alias lives in `agents.domain.event` (F7 took the same
 *    route for `CanonProvenance.perceiveEventId`).
 * 2. **Signals are derived, never stored.** Priority, urgency and trust are computed
 *    from Beliefs, Constraints and Commitment history at read time. No type here has a
 *    `priority`, `urgency` or `trust` member, and none should gain one.
 * 3. **Electrical naming (F27).** `Field` / `Potential` / `Resistance`, in the family
 *    of Transformer, Cell, Switch, Relay and Breaker. `Potential` and `Resistance` are
 *    names only: how they combine into a ranking (F28) is open and is settled
 *    empirically in `ampere-eval`, so no arithmetic is declared on them.
 *
 * Sealed interfaces at the non-leaves, data classes at the leaves, `Ref<out T>` for
 * identity. Nothing here is annotated `@Serializable`: wire shape is a storage
 * decision and every W2 store (AMPR-416, AMPR-417, AMPR-419) maps columns by hand.
 */

/**
 * The root of the Field's type hierarchy: a thing the Field holds state *about*.
 *
 * Exactly three branches — [Subject] (who acts and believes), [Goal] (what is acted
 * toward) and [Environment] (what is acted on and with). Beliefs, Constraints, Actions
 * and Relationships are *records about* primitives rather than primitives themselves,
 * which is why [Ref] is bounded on this type and not on a wider one: a Belief can be
 * about an agent, a goal or an artifact, but never about another Belief.
 *
 * Role-by-subject is the reason there are three branches and not twelve: the loop is
 * written once per branch, not once per role an agent might play.
 */
sealed interface Primitive {

    /** Identity within the Field. Stable across folds; see [Ref]. */
    val id: Ref<Primitive>
}

/**
 * A typed, opaque handle to a [Primitive].
 *
 * Replaces the `String` typealiases (`AgentId`, `ExecutorId`, …) that let an agent id
 * be passed where a goal id was expected and compile. The type parameter is a phantom:
 * only the [value] is stored, and `out` variance means a `Ref<Subject.Agent>` is usable
 * wherever a `Ref<Subject>` or `Ref<Primitive>` is asked for.
 *
 * F30 registers `Ref<Subject.Agent>` as a wrapper over the existing `AgentId` string,
 * so the [value] of an agent ref *is* the agent id — no translation table.
 */
@JvmInline
value class Ref<out T : Primitive>(val value: String)

/**
 * Who acts and who believes. An agent, a human, or a group of either.
 *
 * Every [Belief] has a Subject as its holder; every [Action] has one as its actor; every
 * [Constraint] names one as its origin. Humans are Subjects for the same reason agents
 * are: the Principal has beliefs the Field must model (F12), and a human decision is an
 * Action like any other.
 */
sealed interface Subject : Primitive {

    override val id: Ref<Subject>

    /** The holder's own label; display only, never identity. */
    val name: String

    /** The event that registered this Subject (F30). */
    val provenance: Provenance

    /** An Ampere agent. [id] wraps the existing `AgentId` string (F30). */
    data class Agent(
        override val id: Ref<Agent>,
        override val name: String,
        override val provenance: Provenance,
    ) : Subject

    /** A person. The Principal is the first Human row (F30). */
    data class Human(
        override val id: Ref<Human>,
        override val name: String,
        override val provenance: Provenance,
    ) : Subject

    /**
     * A set of Subjects that acts or is addressed as one — an `AgentTeam`, a Room's
     * membership. Membership is not a member of this class: it is a
     * [Relationship] of kind [RelationshipKind.MemberOf], so joining and leaving are
     * events in the fold rather than mutations of a list.
     */
    data class Group(
        override val id: Ref<Group>,
        override val name: String,
        override val provenance: Provenance,
    ) : Subject
}

/**
 * What is acted toward.
 *
 * The agent's *own* goal — a `GoalNode` of the Arc's `GoalTree`, persisted (F8). A
 * tracker's view of the same work (`CanonWorkItem`, `CanonProject`) is perceived
 * [Environment], linked to the Goal by a [Relationship] of kind
 * [RelationshipKind.Mirrors]. Keeping the two apart is what stops the two-model
 * problem from re-forming inside the Field.
 *
 * @property parent The enclosing goal, or `null` at the root of a tree. Hierarchy
 *   depth is one candidate input to a goal's weight (F28, open).
 */
data class Goal(
    override val id: Ref<Goal>,
    val description: String,
    val parent: Ref<Goal>? = null,
    val provenance: Provenance,
) : Primitive

/**
 * What is acted on and with: affordances, resources, artifacts.
 *
 * A marker only. The canon (`link.socket.ampere.canon`) *is* the Environment branch
 * (F7): its 33 provenance-carrying entities enter the Field as Observations through
 * `CanonEntity` and `CanonProvenance : Observed`, and this package re-declares none of
 * them. The marker is an open interface rather than a sealed one for exactly that
 * reason — the implementations live in another package.
 */
interface Environment : Primitive
