package link.socket.ampere.field

/**
 * The Field as one Subject may see it, read at one instant (AMPR-439, F6).
 *
 * A snapshot, not a fold: every W2 door publishes its event and writes its row in one step, so
 * the stores already *are* the fold of the log, and a Field is a read over them. Nothing here is
 * rebuilt from events, and nothing here is stored — a Field is computed on request and dropped.
 *
 * **Signals are derived from it, never stored on it.** Priority, urgency, trust, Potential and
 * Resistance are computed from a Field by whoever reads one; none is a member here, and how they
 * combine (F28) is open.
 *
 * **Always a view for someone (F12).** [beliefs] holds what [viewer] may see: every
 * [Sensitivity.PUBLIC] belief plus the ones [viewer] holds. Another Subject's
 * [Sensitivity.PRIVATE] and [Sensitivity.PROTECTED] beliefs are not here, and a `null` viewer
 * — nobody — sees public beliefs only. Subjects, relationships and goals carry no sensitivity
 * and are the same in every view.
 *
 * Not here yet: Observations (the Environment branch's content — nothing stores them), Actions
 * and Constraints. Each has an open question on AMPR-328, and a Field without them is the Field
 * the stores can answer for today.
 *
 * @property viewer Whose view this is; `null` for nobody's.
 * @property asOfSequence The newest event `sequence` in the log when the read began, `0` for an
 *   empty log. It anchors the snapshot to the log; it is not a transactional cut. The stores are
 *   read after it, so a row whose event came later may appear, and a write in flight at that
 *   moment may not have landed yet.
 * @property subjects Every registered Subject, in registration order — the `system`
 *   pseudo-Subject included.
 * @property relationships Every edge touching a listed Subject, from either end, once each.
 * @property goals Every listed Subject's goals, open and completed.
 * @property beliefs What [viewer] may see, newest first, up to the limit the read asked for.
 * @property unmappedBeliefs Visible rows that could not be read as a [Belief] — a knowledge row
 *   older than its provenance column names no event, and a belief with an invented provenance
 *   would be a lie about where it came from. Counted rather than dropped silently.
 */
data class Field(
    val viewer: Ref<Subject>?,
    val asOfSequence: Long,
    val subjects: List<Subject>,
    val relationships: List<Relationship<Primitive, Primitive>>,
    val goals: List<HeldGoal>,
    val beliefs: List<Belief>,
    val unmappedBeliefs: Int = 0,
) {

    /**
     * A [Goal] on the branch with what only its row knows: whose it is and where it stands.
     *
     * @property subject The Subject that formed it.
     * @property state Whether it is still being worked toward.
     */
    data class HeldGoal(
        val goal: Goal,
        val subject: Ref<Subject>,
        val state: GoalState,
    )

    /** Where a [HeldGoal] stands. No weight or rank: how much a goal pulls is F28, open. */
    enum class GoalState {
        /** Formed and not yet met. */
        OPEN,

        /** Met. */
        COMPLETED,
    }
}
