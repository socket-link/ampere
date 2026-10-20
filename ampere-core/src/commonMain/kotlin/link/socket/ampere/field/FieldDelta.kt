package link.socket.ampere.field

/**
 * One goal whose state moved between two [Field] snapshots.
 *
 * @property goal The goal as read in the later snapshot.
 * @property from Its state in the earlier snapshot.
 * @property to Its state in the later one. Never equal to [from]: a goal whose state did not
 *   move is not a change.
 */
data class GoalChange(
    val goal: Goal,
    val from: Field.GoalState,
    val to: Field.GoalState,
)

/**
 * What moved between two reads of the Field (AMPR-479, epic AMPR-478 row N2).
 *
 * A fold of two snapshots, not a replay of the log: the Field's stores already are the fold, so
 * the difference between two Fields is the difference between two values, keyed on the identity
 * every primitive already carries. A delta is never stored and never put on an event; it is
 * computed from two snapshots a caller holds and dropped. Rendering it for a prompt or a decision
 * is outside this leaf.
 *
 * The identities folded on:
 *
 * - a [Belief] or [Belief.Settled] observation by `(provenance.createdBy, holder)`;
 * - a [Commitment] by `provenance.createdBy`;
 * - an [Action] by [Action.id];
 * - a [Field.HeldGoal] by `goal.id`, with its [Field.GoalState];
 * - a [Subject] by [Subject.id];
 * - a [Relationship] by `(from, to, kind)`.
 *
 * **This run's own work is left out.** [actionsByOthers] omits any new Action whose
 * [Action.actor] is one of the `ownActors` the fold was given, because a run's planner is
 * already shown its own work as step results (AMPR-424); what the receptor wants is what the
 * rest of the world did.
 *
 * **Protected rows stay in.** A delta is the host's own view, and a renderer decides what leaves
 * the device, so a [Sensitivity.PROTECTED] belief or observation is in its list and counted on
 * [protectedLeftOut]. A delta of only protected rows is therefore not [isEmpty].
 *
 * @property fromSequence The earlier snapshot's `asOfSequence`.
 * @property toSequence The later snapshot's `asOfSequence`.
 * @property goalsChanged Goals whose state differs between the two reads, in the later
 *   snapshot's order. A goal present only in the later read is not a change of state and is not
 *   here.
 * @property commitmentsOpened Commitments open in the later read and absent from the earlier.
 * @property commitmentsResolved Commitments open in the earlier read that are absent from the
 *   later one or carried there as [Commitment.Status.RESOLVED], as read in the later snapshot
 *   when it has them and in the earlier one otherwise.
 * @property beliefsAdded Beliefs in the later read and not in the earlier, by provenance and
 *   holder.
 * @property observationsAdded Observations in the later read and not in the earlier, by
 *   provenance and holder.
 * @property actionsByOthers Actions in the later read and not in the earlier whose actor is not
 *   one of the fold's own actors.
 * @property subjectsAdded Subjects registered between the two reads.
 * @property relationshipsAdded Edges present in the later read and not in the earlier.
 * @property protectedLeftOut How many of [beliefsAdded] and [observationsAdded] are
 *   [Sensitivity.PROTECTED]. A count of rows that *are* in the lists, for a renderer to act on;
 *   nothing is dropped here.
 */
data class FieldDelta(
    val fromSequence: Long,
    val toSequence: Long,
    val goalsChanged: List<GoalChange>,
    val commitmentsOpened: List<Commitment>,
    val commitmentsResolved: List<Commitment>,
    val beliefsAdded: List<Belief>,
    val observationsAdded: List<Belief.Settled>,
    val actionsByOthers: List<Action>,
    val subjectsAdded: List<Subject>,
    val relationshipsAdded: List<Relationship<Primitive, Primitive>>,
    val protectedLeftOut: Int,
) {

    /** True when nothing moved: every list is empty. [protectedLeftOut] does not decide it. */
    val isEmpty: Boolean
        get() = goalsChanged.isEmpty() &&
            commitmentsOpened.isEmpty() &&
            commitmentsResolved.isEmpty() &&
            beliefsAdded.isEmpty() &&
            observationsAdded.isEmpty() &&
            actionsByOthers.isEmpty() &&
            subjectsAdded.isEmpty() &&
            relationshipsAdded.isEmpty()

    companion object {

        /**
         * Fold [before] and [after] into what moved.
         *
         * Deterministic: the same two snapshots fold to an equal delta every time, and every list
         * keeps [after]'s order (or [before]'s, for a commitment that vanished). Two snapshots
         * with the same `asOfSequence` fold to the empty delta without comparing anything.
         *
         * @param ownActors The Subjects whose Actions are this run's own work and so left out of
         *   [actionsByOthers].
         */
        fun between(
            before: Field,
            after: Field,
            ownActors: Set<Ref<Subject>>,
        ): FieldDelta {
            if (before.asOfSequence == after.asOfSequence) {
                return empty(before.asOfSequence, after.asOfSequence)
            }

            val goalsBefore = before.goals.associateBy { it.goal.id }
            val goalsChanged = after.goals.mapNotNull { held ->
                val earlier = goalsBefore[held.goal.id] ?: return@mapNotNull null
                if (earlier.state == held.state) {
                    null
                } else {
                    GoalChange(goal = held.goal, from = earlier.state, to = held.state)
                }
            }

            val openBefore = before.commitments
                .filter { it.status == Commitment.Status.OPEN }
                .associateBy { it.provenance.createdBy }
            val afterByEvent = after.commitments.associateBy { it.provenance.createdBy }
            val commitmentsOpened = after.commitments.filter {
                it.status == Commitment.Status.OPEN && it.provenance.createdBy !in openBefore
            }
            val commitmentsResolved = openBefore.values.mapNotNull { earlier ->
                val later = afterByEvent[earlier.provenance.createdBy]
                when {
                    later == null -> earlier
                    later.status == Commitment.Status.RESOLVED -> later
                    else -> null
                }
            }

            val beliefsBefore = before.beliefs.mapTo(HashSet()) { it.beliefKey() }
            val beliefsAdded = after.beliefs.filter { it.beliefKey() !in beliefsBefore }
            val observationsBefore = before.observations.mapTo(HashSet()) { it.beliefKey() }
            val observationsAdded = after.observations.filter { it.beliefKey() !in observationsBefore }

            val actionsBefore = before.actions.mapTo(HashSet()) { it.id }
            val actionsByOthers = after.actions.filter { it.id !in actionsBefore && it.actor !in ownActors }

            val subjectsBefore = before.subjects.mapTo(HashSet()) { it.id }
            val subjectsAdded = after.subjects.filter { it.id !in subjectsBefore }

            val relationshipsBefore = before.relationships.mapTo(HashSet()) { it.edgeKey() }
            val relationshipsAdded = after.relationships.filter { it.edgeKey() !in relationshipsBefore }

            val protectedLeftOut = beliefsAdded.count { it.sensitivity == Sensitivity.PROTECTED } +
                observationsAdded.count { it.sensitivity == Sensitivity.PROTECTED }

            return FieldDelta(
                fromSequence = before.asOfSequence,
                toSequence = after.asOfSequence,
                goalsChanged = goalsChanged,
                commitmentsOpened = commitmentsOpened,
                commitmentsResolved = commitmentsResolved,
                beliefsAdded = beliefsAdded,
                observationsAdded = observationsAdded,
                actionsByOthers = actionsByOthers,
                subjectsAdded = subjectsAdded,
                relationshipsAdded = relationshipsAdded,
                protectedLeftOut = protectedLeftOut,
            )
        }

        private fun empty(fromSequence: Long, toSequence: Long): FieldDelta =
            FieldDelta(
                fromSequence = fromSequence,
                toSequence = toSequence,
                goalsChanged = emptyList(),
                commitmentsOpened = emptyList(),
                commitmentsResolved = emptyList(),
                beliefsAdded = emptyList(),
                observationsAdded = emptyList(),
                actionsByOthers = emptyList(),
                subjectsAdded = emptyList(),
                relationshipsAdded = emptyList(),
                protectedLeftOut = 0,
            )

        private fun Belief.beliefKey(): Pair<String, Ref<Subject>> = provenance.createdBy to holder

        private fun Relationship<Primitive, Primitive>.edgeKey(): EdgeKey = EdgeKey(from, to, kind)

        private data class EdgeKey(
            val from: Ref<Primitive>,
            val to: Ref<Primitive>,
            val kind: RelationshipKind,
        )
    }
}
