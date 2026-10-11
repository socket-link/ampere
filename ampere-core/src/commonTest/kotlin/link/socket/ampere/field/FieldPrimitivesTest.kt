package link.socket.ampere.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant

/**
 * Pins the shape of the Field primitives (AMPR-415): the three branches, the
 * covariance of [Ref], the Settled/Unsettled split, the three-state Action, and the
 * fact that nothing here stores a signal or a predicate.
 *
 * Shape tests only — the package has no behaviour to test.
 */
class FieldPrimitivesTest {

    private val at = Instant.parse("2026-10-11T00:00:00Z")
    private val provenance = Provenance(createdBy = "evt-1", createdAt = at)
    private val agent = Subject.Agent(Ref("planner"), "Planner", provenance)
    private val human = Subject.Human(Ref("miley"), "Miley", provenance)
    private val team = Subject.Group(Ref("team-1"), "Blueprint", provenance)
    private val goal = Goal(Ref("goal-1"), "Ship AMPR-415", provenance = provenance)

    @Test
    fun `the three subject kinds are the only Subjects and every Subject is a Primitive`() {
        val subjects: List<Subject> = listOf(agent, human, team)
        subjects.forEach { assertIs<Primitive>(it) }
        assertEquals(listOf("planner", "miley", "team-1"), subjects.map { it.id.value })
    }

    @Test
    fun `a Ref to an Agent is usable as a Ref to a Subject and to a Primitive`() {
        val agentRef: Ref<Subject.Agent> = agent.id
        val subjectRef: Ref<Subject> = agentRef
        val primitiveRef: Ref<Primitive> = agentRef
        assertEquals(agentRef.value, subjectRef.value)
        assertEquals(agentRef.value, primitiveRef.value)
    }

    @Test
    fun `a settled belief carries a number and an unsettled one carries none`() {
        val settled = Belief.Settled(
            holder = agent.id,
            subject = goal.id,
            content = "The tests cover the change",
            confidence = 0.75,
            sensitivity = Sensitivity.PUBLIC,
            provenance = provenance,
        )
        val unsettled = Belief.Unsettled(
            holder = agent.id,
            subject = goal.id,
            content = "Whether the iOS target compiles",
            sensitivity = Sensitivity.PRIVATE,
            decay = Decay.HalfLife(2.hours),
            provenance = provenance,
        )
        val beliefs: List<Belief> = listOf(settled, unsettled)

        assertEquals(0.75, settled.confidence)
        assertNull(unsettled.confidence)
        assertEquals(listOf(0.75, null), beliefs.map { it.confidence })
        assertEquals(Decay.None, settled.decay)
    }

    @Test
    fun `a belief may be about any primitive branch`() {
        fun about(subject: Ref<Primitive>) = Belief.Unsettled(
            holder = human.id,
            subject = subject,
            content = "about",
            sensitivity = Sensitivity.PROTECTED,
            provenance = provenance,
        )
        assertEquals(agent.id.value, about(agent.id).subject.value)
        assertEquals(goal.id.value, about(goal.id).subject.value)
    }

    @Test
    fun `constraints are data and every rule case is constructible`() {
        val rules: List<Rule> = listOf(
            Rule.Requirements(),
            Rule.Quota.Time(1.hours),
            Rule.Quota.Count(limit = 10, of = "files"),
            Rule.Quota.Tokens(50_000),
            Rule.Transition(mapOf("OPEN" to setOf("IN_PROGRESS"))),
            Rule.AccessPolicy(setOf("calendar.read")),
            Rule.Dependency(setOf(goal.id)),
            Rule.Deadline(at),
            Rule.Cadence(24.hours),
        )
        val constraints = rules.map { rule ->
            Constraint(goal.id, rule, Hardness.HARD, origin = human.id, provenance = provenance)
        }

        assertEquals(rules.size, constraints.size)
        assertTrue(constraints.all { it.origin == human.id })
        assertEquals(Hardness.entries.size, 2)
    }

    @Test
    fun `an action keeps actor and executor apart and moves forward through three states`() {
        val action = Action(
            id = ActionId("act-1"),
            actor = agent.id,
            executor = "claude-code",
            goal = goal.id,
            description = "Run the tests",
            state = ActionState.PROPOSED,
            reversibility = Reversibility.REVERSIBLE,
            cost = Cost(watts = 0.4),
            prediction = Prediction(outcome = "green", confidence = 0.6),
            provenance = provenance,
        )

        assertEquals("planner", action.actor.value)
        assertEquals("claude-code", action.executor)
        assertEquals(
            listOf(ActionState.PROPOSED, ActionState.ADMITTED, ActionState.TAKEN),
            ActionState.entries.toList(),
        )
        assertEquals(ActionState.ADMITTED, action.copy(state = ActionState.ADMITTED).state)
    }

    @Test
    fun `membership and mirroring are relationships not members`() {
        val membership = Relationship(
            from = agent.id,
            to = team.id,
            kind = RelationshipKind.MemberOf,
            provenance = provenance,
        )
        val mirror = Relationship(
            from = Ref<Environment>("work-item-7"),
            to = goal.id,
            kind = RelationshipKind.Mirrors,
            provenance = provenance,
        )

        assertEquals(RelationshipKind.MemberOf, membership.kind)
        assertEquals(RelationshipKind.Mirrors, mirror.kind)
        assertEquals("goal-1", mirror.to.value)
    }

    @Test
    fun `potential and resistance are names over a double with no arithmetic`() {
        val potential = Potential(0.8)
        val resistance = Resistance(0.3)
        assertEquals(0.8, potential.value)
        assertEquals(0.3, resistance.value)
    }
}
