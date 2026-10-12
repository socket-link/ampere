package link.socket.ampere.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant

/**
 * Pins the shape of the Field primitives (AMPR-415): the three branches, the
 * covariance of [Ref], the Settled/Unsettled split, the three-state Action, and the
 * fact that nothing here stores a signal or a predicate. AMPR-448 adds a belief's kind and
 * an Action whose reversibility and cost may be unknown and whose result only a taken
 * Action has.
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
            kind = BeliefKind.LESSON,
            confidence = 0.75,
            sensitivity = Sensitivity.PUBLIC,
            provenance = provenance,
        )
        val unsettled = Belief.Unsettled(
            holder = agent.id,
            subject = goal.id,
            content = "Whether the iOS target compiles",
            kind = BeliefKind.TASK,
            sensitivity = Sensitivity.PRIVATE,
            decay = Decay.HalfLife(2.hours),
            provenance = provenance,
        )
        val beliefs: List<Belief> = listOf(settled, unsettled)

        assertEquals(0.75, settled.confidence)
        assertNull(unsettled.confidence)
        assertEquals(listOf(0.75, null), beliefs.map { it.confidence })
        assertEquals(Decay.None, settled.decay)
        assertEquals(listOf(BeliefKind.LESSON, BeliefKind.TASK), beliefs.map { it.kind })
    }

    @Test
    fun `the belief kinds are the knowledge sources with outcome read as lesson and the observation`() {
        assertEquals(
            listOf(
                BeliefKind.LESSON,
                BeliefKind.IDEA,
                BeliefKind.PERCEPTION,
                BeliefKind.PLAN,
                BeliefKind.TASK,
                BeliefKind.OBSERVATION,
            ),
            BeliefKind.entries.toList(),
        )
    }

    @Test
    fun `a belief may be about any primitive branch`() {
        fun about(subject: Ref<Primitive>) = Belief.Unsettled(
            holder = human.id,
            subject = subject,
            content = "about",
            kind = BeliefKind.IDEA,
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
        assertNull(action.result)
    }

    @Test
    fun `an action may leave reversibility and cost unknown`() {
        val action = Action(
            id = ActionId("act-2"),
            actor = agent.id,
            description = "read_codebase",
            state = ActionState.TAKEN,
            reversibility = null,
            cost = null,
            provenance = provenance,
        )

        assertNull(action.reversibility)
        assertNull(action.cost)
        assertNull(action.result, "a taken action whose outcome is unknown has no result")
    }

    @Test
    fun `a failed action is a taken one whose result failed`() {
        val taken = Action(
            id = ActionId("act-3"),
            actor = agent.id,
            executor = "function-executor",
            description = "run_tests",
            state = ActionState.TAKEN,
            reversibility = null,
            cost = null,
            result = ActionResult.Failed(reason = "two tests failed"),
            provenance = provenance,
        )

        assertEquals(ActionState.TAKEN, taken.state)
        assertEquals("two tests failed", assertIs<ActionResult.Failed>(taken.result).reason)
        assertEquals(ActionResult.Succeeded, taken.copy(result = ActionResult.Succeeded).result)
        assertNull(ActionResult.Failed(reason = null).reason, "no reason is stated rather than invented")
    }

    @Test
    fun `only a taken action has a result`() {
        for (state in listOf(ActionState.PROPOSED, ActionState.ADMITTED)) {
            assertFailsWith<IllegalArgumentException> {
                Action(
                    id = ActionId("act-4"),
                    actor = agent.id,
                    description = "deploy",
                    state = state,
                    reversibility = Reversibility.IRREVERSIBLE,
                    cost = null,
                    result = ActionResult.Succeeded,
                    provenance = provenance,
                )
            }
        }
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
