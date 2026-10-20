package link.socket.ampere.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * Pins [FieldDelta.between] (AMPR-479): a deterministic fold of two snapshots keyed on the
 * identity each primitive already carries, with the run's own Actions left out and protected
 * rows kept and counted.
 */
class FieldDeltaTest {

    private val at = Instant.parse("2026-10-20T00:00:00Z")
    private val host = Subject.Agent(Ref("host"), "Host", provenance("evt-host"))
    private val other = Subject.Agent(Ref("other"), "Other", provenance("evt-other"))
    private val goal = Goal(Ref("goal-1"), "Ship the delta", provenance = provenance("evt-goal"))

    private fun provenance(event: String) = Provenance(createdBy = event, createdAt = at)

    private fun field(
        sequence: Long,
        subjects: List<Subject> = listOf(host, other),
        relationships: List<Relationship<Primitive, Primitive>> = emptyList(),
        goals: List<Field.HeldGoal> = emptyList(),
        beliefs: List<Belief> = emptyList(),
        observations: List<Belief.Settled> = emptyList(),
        commitments: List<Commitment> = emptyList(),
        actions: List<Action> = emptyList(),
    ) = Field(
        viewer = host.id,
        asOfSequence = sequence,
        subjects = subjects,
        relationships = relationships,
        goals = goals,
        beliefs = beliefs,
        observations = observations,
        commitments = commitments,
        actions = actions,
    )

    private fun belief(event: String, sensitivity: Sensitivity = Sensitivity.PUBLIC) = Belief.Settled(
        holder = host.id,
        subject = goal.id,
        content = "learned from $event",
        kind = BeliefKind.LESSON,
        confidence = 0.5,
        sensitivity = sensitivity,
        provenance = provenance(event),
    )

    private fun observation(event: String, sensitivity: Sensitivity) = Belief.Settled(
        holder = host.id,
        subject = Ref<Primitive>("entity-1"),
        content = "perceived under $event",
        kind = BeliefKind.OBSERVATION,
        confidence = 1.0,
        sensitivity = sensitivity,
        provenance = provenance(event),
    )

    private fun commitment(event: String, status: Commitment.Status) = Commitment(
        committer = host.id,
        ticketId = "ticket-1",
        due = at,
        status = status,
        provenance = provenance(event),
        resolvedBy = "evt-done".takeIf { status == Commitment.Status.RESOLVED },
        resolvedAt = at.takeIf { status == Commitment.Status.RESOLVED },
    )

    private fun action(id: String, actor: Subject) = Action(
        id = ActionId(id),
        actor = actor.id,
        description = "tool $id",
        state = ActionState.TAKEN,
        reversibility = null,
        cost = null,
        result = ActionResult.Succeeded,
        provenance = provenance("evt-$id"),
    )

    @Test
    fun `equal sequences fold to the empty delta without comparing`() {
        val before = field(sequence = 7)
        val after = field(sequence = 7, beliefs = listOf(belief("evt-b1")), subjects = emptyList())

        val delta = FieldDelta.between(before, after, ownActors = emptySet())

        assertTrue(delta.isEmpty)
        assertEquals(7L, delta.fromSequence)
        assertEquals(7L, delta.toSequence)
        assertEquals(0, delta.protectedLeftOut)
    }

    @Test
    fun `a belief added by provenance is in beliefsAdded and an unchanged one is not`() {
        val kept = belief("evt-b1")
        val added = belief("evt-b2")
        val before = field(sequence = 1, beliefs = listOf(kept))
        val after = field(sequence = 2, beliefs = listOf(added, kept))

        val delta = FieldDelta.between(before, after, ownActors = emptySet())

        assertEquals(listOf(added), delta.beliefsAdded)
        assertFalse(delta.isEmpty)
        assertEquals(1L, delta.fromSequence)
        assertEquals(2L, delta.toSequence)
    }

    @Test
    fun `a goal moving from open to completed is one GoalChange`() {
        val before = field(sequence = 1, goals = listOf(Field.HeldGoal(goal, host.id, Field.GoalState.OPEN)))
        val after = field(sequence = 2, goals = listOf(Field.HeldGoal(goal, host.id, Field.GoalState.COMPLETED)))

        val delta = FieldDelta.between(before, after, ownActors = emptySet())

        assertEquals(
            listOf(GoalChange(goal, from = Field.GoalState.OPEN, to = Field.GoalState.COMPLETED)),
            delta.goalsChanged,
        )
    }

    @Test
    fun `a goal with the same state in both reads is not a change`() {
        val held = Field.HeldGoal(goal, host.id, Field.GoalState.OPEN)
        val delta = FieldDelta.between(
            field(sequence = 1, goals = listOf(held)),
            field(sequence = 2, goals = listOf(held)),
            ownActors = emptySet(),
        )

        assertTrue(delta.goalsChanged.isEmpty())
        assertTrue(delta.isEmpty)
    }

    @Test
    fun `a commitment open before and resolved after is in commitmentsResolved`() {
        val open = commitment("evt-c1", Commitment.Status.OPEN)
        val resolved = commitment("evt-c1", Commitment.Status.RESOLVED)
        val before = field(sequence = 1, commitments = listOf(open))
        val after = field(sequence = 2, commitments = listOf(resolved))

        val delta = FieldDelta.between(before, after, ownActors = emptySet())

        assertEquals(listOf(resolved), delta.commitmentsResolved)
        assertTrue(delta.commitmentsOpened.isEmpty())
    }

    @Test
    fun `a commitment open before and absent after is resolved and a new open one is opened`() {
        val gone = commitment("evt-c1", Commitment.Status.OPEN)
        val opened = commitment("evt-c2", Commitment.Status.OPEN)
        val before = field(sequence = 1, commitments = listOf(gone))
        val after = field(sequence = 2, commitments = listOf(opened))

        val delta = FieldDelta.between(before, after, ownActors = emptySet())

        assertEquals(listOf(gone), delta.commitmentsResolved)
        assertEquals(listOf(opened), delta.commitmentsOpened)
    }

    @Test
    fun `an action by an own actor is left out and one by another is kept`() {
        val own = action("a-own", host)
        val theirs = action("a-theirs", other)
        val before = field(sequence = 1)
        val after = field(sequence = 2, actions = listOf(own, theirs))

        val delta = FieldDelta.between(before, after, ownActors = setOf(host.id))

        assertEquals(listOf(theirs), delta.actionsByOthers)
    }

    @Test
    fun `an action already in the earlier read is not a new one`() {
        val theirs = action("a-theirs", other)
        val delta = FieldDelta.between(
            field(sequence = 1, actions = listOf(theirs)),
            field(sequence = 2, actions = listOf(theirs)),
            ownActors = emptySet(),
        )

        assertTrue(delta.actionsByOthers.isEmpty())
    }

    @Test
    fun `a protected observation is counted and present`() {
        val protectedRow = observation("evt-o1", Sensitivity.PROTECTED)
        val privateRow = observation("evt-o2", Sensitivity.PRIVATE)
        val before = field(sequence = 1)
        val after = field(sequence = 2, observations = listOf(protectedRow, privateRow))

        val delta = FieldDelta.between(before, after, ownActors = emptySet())

        assertEquals(listOf(protectedRow, privateRow), delta.observationsAdded)
        assertEquals(1, delta.protectedLeftOut)
    }

    @Test
    fun `a delta of only protected rows is not empty`() {
        val before = field(sequence = 1)
        val after = field(
            sequence = 2,
            beliefs = listOf(belief("evt-b1", Sensitivity.PROTECTED)),
            observations = listOf(observation("evt-o1", Sensitivity.PROTECTED)),
        )

        val delta = FieldDelta.between(before, after, ownActors = emptySet())

        assertFalse(delta.isEmpty)
        assertEquals(2, delta.protectedLeftOut)
        assertEquals(1, delta.beliefsAdded.size)
        assertEquals(1, delta.observationsAdded.size)
    }

    @Test
    fun `a new subject and a new edge are added and an existing edge is not`() {
        val third = Subject.Agent(Ref("third"), "Third", provenance("evt-third"))
        val existing =
            Relationship<Primitive, Primitive>(host.id, other.id, RelationshipKind.MemberOf, provenance("evt-r1"))
        val added =
            Relationship<Primitive, Primitive>(third.id, other.id, RelationshipKind.MemberOf, provenance("evt-r2"))
        val before = field(sequence = 1, relationships = listOf(existing))
        val after = field(sequence = 2, subjects = listOf(host, other, third), relationships = listOf(existing, added))

        val delta = FieldDelta.between(before, after, ownActors = emptySet())

        assertEquals(listOf(third), delta.subjectsAdded)
        assertEquals(listOf(added), delta.relationshipsAdded)
    }

    @Test
    fun `a delta folded twice from the same two snapshots is equal`() {
        val before = field(
            sequence = 1,
            goals = listOf(Field.HeldGoal(goal, host.id, Field.GoalState.OPEN)),
            commitments = listOf(commitment("evt-c1", Commitment.Status.OPEN)),
            beliefs = listOf(belief("evt-b1")),
        )
        val after = field(
            sequence = 2,
            goals = listOf(Field.HeldGoal(goal, host.id, Field.GoalState.ABANDONED)),
            commitments = listOf(commitment("evt-c2", Commitment.Status.OPEN)),
            beliefs = listOf(belief("evt-b2", Sensitivity.PROTECTED), belief("evt-b1")),
            observations = listOf(observation("evt-o1", Sensitivity.PRIVATE)),
            actions = listOf(action("a-own", host), action("a-theirs", other)),
        )

        val first = FieldDelta.between(before, after, ownActors = setOf(host.id))
        val second = FieldDelta.between(before, after, ownActors = setOf(host.id))

        assertEquals(first, second)
        assertFalse(first.isEmpty)
    }
}
