package link.socket.ampere.field

/**
 * A structural edge between two primitives.
 *
 * Structure is kept as edges rather than as nested members (a Group holding a list of
 * agents, a Goal holding its mirror) so that joining, leaving and linking are events in
 * the fold and so that an edge can be queried from either end. The type parameters are
 * phantoms carried by the [Ref]s: a `Relationship<Subject, Subject.Group>` of kind
 * [RelationshipKind.MemberOf] cannot be built from a `Ref<Goal>`.
 *
 * @property from The edge's source — the member, the mirror.
 * @property to The edge's target — the group, the thing mirrored.
 * @property kind What the edge means; see [RelationshipKind].
 */
data class Relationship<out From : Primitive, out To : Primitive>(
    val from: Ref<From>,
    val to: Ref<To>,
    val kind: RelationshipKind,
    val provenance: Provenance,
)

/**
 * The closed set of meanings a [Relationship] can carry.
 *
 * Sealed rather than an enum so a later kind can carry data without a schema change
 * here; the two kinds below are the ones W2 needs.
 */
sealed interface RelationshipKind {

    /**
     * `from` is an external view of `to` — a `CanonWorkItem` perceived from a tracker
     * mirrors the agent's own [Goal] (F8). Neither side owns the other; the tracker's
     * status and the Field's goal state are reconciled, not merged.
     */
    data object Mirrors : RelationshipKind

    /**
     * `from` belongs to `to` — an agent in an `AgentTeam`, a human in a Room (F30).
     * Membership is this edge and nothing else; [Subject.Group] has no member list.
     */
    data object MemberOf : RelationshipKind
}
