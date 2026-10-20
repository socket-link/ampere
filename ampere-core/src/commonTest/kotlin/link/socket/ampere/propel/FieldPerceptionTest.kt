package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.execution.DATA_BLOCK_BEGIN
import link.socket.ampere.agents.execution.DATA_BLOCK_END
import link.socket.ampere.agents.execution.DATA_BLOCK_PREAMBLE
import link.socket.ampere.field.Action
import link.socket.ampere.field.ActionId
import link.socket.ampere.field.ActionResult
import link.socket.ampere.field.ActionState
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Environment
import link.socket.ampere.field.Field
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject

/**
 * The "# World" section (AMPR-480, row N10): what of a [Field] reaches the host seat's PERCEIVE
 * prompt, and — the load-bearing half — what never does.
 */
class FieldPerceptionTest {

    @Test
    fun `Off renders nothing whatever the Field holds`() {
        assertNull(populated().section(FieldScope.Off, policy = ObservePolicy()))
    }

    @Test
    fun `an empty Goal projection renders nothing`() {
        val empty = Field(
            viewer = HOST,
            asOfSequence = 0,
            subjects = emptyList(),
            relationships = emptyList(),
            goals = emptyList(),
            beliefs = emptyList(),
            observations = emptyList(),
        )
        assertNull(empty.section(FieldScope.Goal))
        assertNull(empty.section(FieldScope.Full))
    }

    @Test
    fun `a Goal projection of other work only renders nothing`() {
        // A belief about someone else's goal, held by someone else, and an Action this run's own
        // seat took: nothing in scope for Goal, so no section rather than an empty one.
        val field = empty().copy(
            beliefs = listOf(belief(holder = OTHER, subject = OTHER_GOAL, "theirs")),
            actions = listOf(action(actor = HOST, "search")),
        )
        assertNull(field.section(FieldScope.Goal))
    }

    @Test
    fun `a PROTECTED belief is counted and never rendered`() {
        val field = empty().copy(
            beliefs = listOf(
                belief(holder = HOST, subject = ROOT_GOAL, "the uploader retries three times"),
                belief(holder = HOST, subject = ROOT_GOAL, SECRET, sensitivity = Sensitivity.PROTECTED),
            ),
        )
        val section = assertNotNull(field.section(FieldScope.Goal))

        assertFalse(section.contains(SECRET), "a protected row's content never reaches the prompt: $section")
        assertTrue(section.contains("the uploader retries three times"), section)
        assertTrue(section.contains("(1 protected entry not shown)"), "and the row is counted: $section")
    }

    @Test
    fun `a PROTECTED observation is counted with the beliefs and never rendered`() {
        val field = empty().copy(
            beliefs = listOf(
                belief(holder = HOST, subject = ROOT_GOAL, SECRET, sensitivity = Sensitivity.PROTECTED),
            ),
            observations = listOf(
                observation(holder = HOST, "Observed person p-1 via contacts", Sensitivity.PROTECTED),
                observation(holder = HOST, "Observed work_item w-7 via tracker", Sensitivity.PRIVATE),
            ),
        )
        val section = assertNotNull(field.section(FieldScope.Goal))

        assertFalse(section.contains(SECRET), section)
        assertFalse(section.contains("person p-1"), "a protected observation never reaches the prompt: $section")
        assertTrue(section.contains("OBSERVATION: Observed work_item w-7 via tracker"), section)
        assertTrue(section.contains("(2 protected entries not shown)"), section)
    }

    @Test
    fun `only PROTECTED rows render a section by themselves`() {
        val field = empty().copy(
            beliefs = listOf(
                belief(holder = HOST, subject = ROOT_GOAL, SECRET, sensitivity = Sensitivity.PROTECTED),
            ),
        )
        val section = assertNotNull(field.section(FieldScope.Goal))
        assertFalse(section.contains(SECRET), section)
        assertTrue(section.contains("(1 protected entry not shown)"), section)
        assertFalse(section.contains("[external]"), "no entry was rendered: $section")
    }

    @Test
    fun `every entry carries external and the markers are the data block's`() {
        val section = assertNotNull(populated().section(FieldScope.Goal))
        val lines = section.lines()

        assertEquals("# World", lines.first())
        assertTrue(lines.contains(WORLD_PREAMBLE), section)
        assertTrue(lines.contains(DATA_BLOCK_PREAMBLE), section)
        val begin = lines.indexOf(DATA_BLOCK_BEGIN)
        val end = lines.indexOf(DATA_BLOCK_END)
        assertTrue(begin in 0 until end, "one block opened then closed: $section")
        val entries = lines.subList(begin + 1, end)
        assertTrue(entries.isNotEmpty(), section)
        assertTrue(entries.all { it.startsWith("- [external] ") }, "every entry is labelled: $entries")
        assertTrue(
            lines.drop(1).none { it.startsWith("- ") && !it.startsWith("- [external] ") },
            "no entry outside the block: $section",
        )
    }

    @Test
    fun `Goal keeps what bears on the run and leaves the rest out`() {
        val section = assertNotNull(populated().section(FieldScope.Goal))

        assertTrue(section.contains("goal (open): Add a retry to the uploader"), section)
        assertFalse(section.contains("Someone else's goal"), "another run's goal is out of scope: $section")
        assertTrue(section.contains("LESSON: three attempts were enough"), "a belief about the goal: $section")
        assertTrue(section.contains("IDEA: the host believes this"), "a belief the host holds: $section")
        assertFalse(
            section.contains("theirs about theirs"),
            "a belief neither about the goal nor the host's: $section",
        )
        assertTrue(section.contains("Ada undertook TICKET-7 by 2026-10-21T09:00:00Z"), section)
        assertTrue(section.contains("Ada did fetch_mail — succeeded"), "another actor's action: $section")
        assertTrue(section.contains("Bot did send_reply — failed"), section)
        assertFalse(section.contains("did search"), "this run's own action is left out: $section")
    }

    @Test
    fun `LESSON beliefs come first and the limits cut each branch`() {
        val field = empty().copy(
            beliefs = listOf(
                belief(holder = HOST, subject = ROOT_GOAL, "idea one", kind = BeliefKind.IDEA),
                belief(holder = HOST, subject = ROOT_GOAL, "lesson one", kind = BeliefKind.LESSON),
                belief(holder = HOST, subject = ROOT_GOAL, "idea two", kind = BeliefKind.IDEA),
            ),
            commitments = listOf(
                commitment("TICKET-2", due = Instant.parse("2026-10-23T09:00:00Z")),
                commitment("TICKET-1", due = Instant.parse("2026-10-21T09:00:00Z")),
            ),
            actions = listOf(action(actor = OTHER, "first"), action(actor = OTHER, "second")),
        )
        val policy = ObservePolicy(field = FieldScope.Goal, beliefLimit = 2, commitmentLimit = 1, actionLimit = 1)
        val section = assertNotNull(field.section(FieldScope.Goal, policy = policy))

        val entries = section.lines().filter { it.startsWith("- [external] ") }
        assertEquals(
            listOf(
                "- [external] Ada undertook TICKET-1 by 2026-10-21T09:00:00Z",
                "- [external] LESSON: lesson one",
                "- [external] IDEA: idea one",
                "- [external] Ada did first — succeeded",
            ),
            entries,
        )
    }

    @Test
    fun `content is clipped to the policy's clip`() {
        val long = "x".repeat(50)
        val field = empty().copy(beliefs = listOf(belief(holder = HOST, subject = ROOT_GOAL, long)))
        val section = assertNotNull(field.section(FieldScope.Goal, policy = GOAL_POLICY.copy(clip = 10)))
        assertTrue(section.contains("LESSON: ${"x".repeat(10)}…"), section)
        assertFalse(section.contains(long), section)
    }

    @Test
    fun `Full renders every branch under the same rule`() {
        val field = populated().copy(
            beliefs = populated().beliefs +
                belief(holder = OTHER, subject = OTHER_GOAL, SECRET, sensitivity = Sensitivity.PROTECTED),
        )
        val section = assertNotNull(field.section(FieldScope.Full))

        assertTrue(section.contains("Someone else's goal"), "every goal: $section")
        assertTrue(section.contains("theirs about theirs"), "every visible belief: $section")
        assertFalse(section.contains(SECRET), "and still never a protected one: $section")
        assertTrue(section.contains("(1 protected entry not shown)"), section)
        assertFalse(section.contains("did search"), "own actions stay out under Full too: $section")
    }

    @Test
    fun `with no root goal the Goal scope still shows what the host holds`() {
        val section = assertNotNull(populated().section(FieldScope.Goal, rootGoal = null))
        assertFalse(section.contains("goal (open)"), "no root goal to name: $section")
        assertTrue(section.contains("IDEA: the host believes this"), section)
        assertFalse(
            section.contains("LESSON: three attempts were enough"),
            "about a goal that is not this run's: $section",
        )
    }

    // ------------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------------

    private fun empty(): Field = Field(
        viewer = HOST,
        asOfSequence = 7,
        subjects = listOf(
            Subject.Agent(id = Ref("seat-host"), name = "Host", provenance = PROVENANCE),
            Subject.Agent(id = Ref("seat-bot"), name = "Bot", provenance = PROVENANCE),
            Subject.Human(id = Ref("ada"), name = "Ada", provenance = PROVENANCE),
        ),
        relationships = emptyList(),
        goals = emptyList(),
        beliefs = emptyList(),
        observations = emptyList(),
    )

    private fun populated(): Field = empty().copy(
        goals = listOf(
            Field.HeldGoal(
                goal = Goal(id = ROOT_GOAL, description = "Add a retry to the uploader", provenance = PROVENANCE),
                subject = HOST,
                state = Field.GoalState.OPEN,
            ),
            Field.HeldGoal(
                goal = Goal(id = OTHER_GOAL, description = "Someone else's goal", provenance = PROVENANCE),
                subject = OTHER,
                state = Field.GoalState.COMPLETED,
            ),
        ),
        beliefs = listOf(
            belief(holder = OTHER, subject = ROOT_GOAL, "three attempts were enough", kind = BeliefKind.LESSON),
            belief(holder = HOST, subject = HOST, "the host believes this", kind = BeliefKind.IDEA),
            belief(holder = OTHER, subject = OTHER_GOAL, "theirs about theirs", kind = BeliefKind.IDEA),
        ),
        commitments = listOf(commitment("TICKET-7", due = Instant.parse("2026-10-21T09:00:00Z"))),
        actions = listOf(
            action(actor = OTHER, "fetch_mail"),
            action(actor = BOT, "send_reply", result = ActionResult.Failed("no network")),
            action(actor = HOST, "search"),
        ),
    )

    private fun belief(
        holder: Ref<Subject>,
        subject: Ref<Primitive>,
        content: String,
        sensitivity: Sensitivity = Sensitivity.PUBLIC,
        kind: BeliefKind = BeliefKind.LESSON,
    ): Belief = Belief.Settled(
        holder = holder,
        subject = subject,
        content = content,
        kind = kind,
        confidence = 0.75,
        sensitivity = sensitivity,
        provenance = PROVENANCE,
    )

    /** [Field.asPerceptionSection] as the run calls it, for the host over its own seats. */
    private fun Field.section(
        scope: FieldScope,
        rootGoal: Ref<Goal>? = ROOT_GOAL,
        policy: ObservePolicy = GOAL_POLICY,
    ): String? = asPerceptionSection(scope, rootGoal, HOST, OWN, policy)

    private fun observation(holder: Ref<Subject>, content: String, sensitivity: Sensitivity): Belief.Settled =
        Belief.Settled(
            holder = holder,
            subject = Ref<Environment>("canon/link-1/entity-1"),
            content = content,
            kind = BeliefKind.OBSERVATION,
            confidence = 1.0,
            sensitivity = sensitivity,
            provenance = PROVENANCE,
        )

    private fun commitment(ticketId: String, due: Instant): Commitment = Commitment(
        committer = OTHER,
        ticketId = ticketId,
        due = due,
        status = Commitment.Status.OPEN,
        provenance = PROVENANCE,
    )

    private fun action(
        actor: Ref<Subject>,
        description: String,
        result: ActionResult = ActionResult.Succeeded,
    ): Action = Action(
        id = ActionId("call-$description"),
        actor = actor,
        description = description,
        state = ActionState.TAKEN,
        reversibility = null,
        cost = null,
        result = result,
        provenance = PROVENANCE,
    )

    private companion object {
        val HOST: Ref<Subject> = Ref("seat-host")
        val BOT: Ref<Subject> = Ref("seat-bot")
        val OTHER: Ref<Subject> = Ref("ada")
        val OWN: Set<Ref<Subject>> = setOf(HOST)
        val ROOT_GOAL: Ref<Goal> = Ref("run-1/goal-0")
        val OTHER_GOAL: Ref<Goal> = Ref("run-2/goal-0")
        val PROVENANCE = Provenance(createdBy = "event-1", createdAt = Instant.fromEpochSeconds(0))
        val GOAL_POLICY = ObservePolicy(field = FieldScope.Goal)
        const val SECRET = "Ada's home address is 12 Elm Street"
    }
}
