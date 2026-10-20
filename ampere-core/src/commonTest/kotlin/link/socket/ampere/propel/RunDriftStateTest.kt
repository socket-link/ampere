package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.ContentOrigin
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.field.Action
import link.socket.ampere.field.ActionId
import link.socket.ampere.field.ActionResult
import link.socket.ampere.field.ActionState
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Environment
import link.socket.ampere.field.Field
import link.socket.ampere.field.FieldDelta
import link.socket.ampere.field.Goal
import link.socket.ampere.field.GoalChange
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.llm.decide.version

/**
 * AMPR-481 (N3, N6): the receptor's state and its two questions.
 *
 * The state is pinned as a golden string because its key order and its vocabulary are the
 * contract: a record carries only the state's digest, and the bench measures a model on the
 * shape production renders. The load-bearing half is what never reaches the text — a
 * `PROTECTED` row, an id, a timestamp.
 */
class RunDriftStateTest {

    // ========================================================================
    // The state
    // ========================================================================

    @Test
    fun `renders the golden state in the fixed key order`() {
        assertEquals(GOLDEN_STATE, drift(populated()).asDecisionState(CLIP))
    }

    @Test
    fun `a protected belief is counted and never rendered`() {
        val state = drift(populated()).asDecisionState(CLIP)

        assertFalse(state.contains(SECRET), "nothing a protected row holds reaches the state: $state")
        assertFalse(state.contains("PROTECTED"), "not even that one was protected by name: $state")
        assertTrue(state.contains("\"protected_left_out\":1"), "but it is counted: $state")
        assertEquals(1, Regex("\"kind\":").findAll(state).count(), "the public belief is the only belief rendered")
    }

    @Test
    fun `a protected observation is left out the same way`() {
        val delta = populated().copy(
            observationsAdded = listOf(observation(SECRET, Sensitivity.PROTECTED)),
            protectedLeftOut = 2,
        )
        val state = drift(delta).asDecisionState(CLIP)

        assertFalse(state.contains(SECRET), state)
        assertTrue(state.contains("\"observations\":[]"), "the only observation was protected: $state")
        assertTrue(state.contains("\"protected_left_out\":2"), state)
    }

    @Test
    fun `two renders of equal inputs are equal`() {
        val first = drift(populated()).asDecisionState(CLIP)
        val second = drift(populated()).asDecisionState(CLIP)

        assertEquals(first, second, "the digest is the only join key back, so one moment is one string")
    }

    @Test
    fun `the state carries no id and no timestamp`() {
        val state = drift(populated()).asDecisionState(CLIP)

        assertFalse(state.contains(RUN_ID), "the run id: $state")
        assertFalse(state.contains("goal-1"), "the goal's id: $state")
        assertFalse(state.contains("action-1"), "the Action's id: $state")
        assertFalse(state.contains("event-"), "a provenance: $state")
        assertFalse(state.contains("13:00"), "a commitment's time of day: only its date was undertaken: $state")
        assertFalse(state.contains("1970"), "a step's timestamps: $state")
        assertFalse(state.contains("outcome-1"), "a step's outcome id: $state")
    }

    @Test
    fun `an empty delta renders changes with every list empty`() {
        val state = drift(noDrift(), cycleResults = emptyList()).asDecisionState(CLIP)

        assertEquals(
            "{\"goal\":\"$GOAL_TEXT\",\"cycle\":2,\"perceived\":\"$PERCEIVED\"," +
                "\"changes\":{\"goals\":[],\"commitments\":{\"opened\":[],\"resolved\":[]}," +
                "\"beliefs\":[],\"observations\":[],\"actions\":[],\"subjects\":[],\"protected_left_out\":0}," +
                "\"cycle_results\":[]}",
            state,
        )
    }

    @Test
    fun `the delta of two equal snapshots renders the same empty changes`() {
        val field = Field(
            viewer = HOST,
            asOfSequence = 3,
            subjects = emptyList(),
            relationships = emptyList(),
            goals = emptyList(),
            beliefs = emptyList(),
            observations = emptyList(),
        )
        val folded = FieldDelta.between(field, field, ownActors = emptySet())

        assertEquals(
            drift(noDrift(), cycleResults = emptyList()).asDecisionState(CLIP),
            drift(folded, cycleResults = emptyList()).asDecisionState(CLIP),
            "sequences are not rendered, so an empty fold and the no-snapshot delta are one state",
        )
    }

    @Test
    fun `every text is clipped and a reference with no name renders as its value`() {
        val state = drift(populated(), names = emptyMap()).asDecisionState(clip = 8)

        assertTrue(state.contains("\"holder\":\"seat-hos…\""), "a nameless ref is its value, clipped: $state")
        assertTrue(state.contains("\"content\":\"the uplo…\""), state)
        assertTrue(state.contains("\"result\":\"found it\""), "eight characters fit: $state")
        assertTrue(state.contains("\"perceived\":\"the seat…\""), state)
    }

    @Test
    fun `a clip below one is refused`() {
        assertFailsWith<IllegalArgumentException> { drift(populated()).asDecisionState(clip = 0) }
    }

    @Test
    fun `an untaken action by another renders as pending`() {
        val proposed = action(result = null, state = ActionState.PROPOSED)
        val state = drift(populated().copy(actionsByOthers = listOf(proposed))).asDecisionState(CLIP)

        assertTrue(state.contains("\"result\":\"pending\""), state)
    }

    // ========================================================================
    // The questions
    // ========================================================================

    @Test
    fun `both receptor questions are nouls answered true or false`() {
        assertEquals(listOf("true", "false"), RunQuestions.perceptionDrift.answerKeys)
        assertEquals(listOf("true", "false"), RunQuestions.lessonWorthKeeping.answerKeys)
        assertEquals("perception_drift", RunQuestions.PERCEPTION_DRIFT_ID)
        assertEquals("lesson_worth_keeping", RunQuestions.LESSON_WORTH_KEEPING_ID)
    }

    @Test
    fun `each question has a pinned version`() {
        // A changed word is a new version, and a reader joining records across releases
        // needs to know when that happened; these pins are what make the change deliberate.
        assertEquals(PERCEPTION_DRIFT_VERSION, RunQuestions.perceptionDrift.version)
        assertEquals(LESSON_WORTH_KEEPING_VERSION, RunQuestions.lessonWorthKeeping.version)
        assertNotEquals(RunQuestions.goalMet.version, RunQuestions.perceptionDrift.version)
        assertNotEquals(RunQuestions.perceptionDrift.version, RunQuestions.lessonWorthKeeping.version)
    }

    @Test
    fun `the questions name the state keys they read`() {
        assertTrue(RunQuestions.perceptionDrift.instructions.contains("`changes`"))
        assertTrue(RunQuestions.lessonWorthKeeping.instructions.contains("`cycle_results`"))
        assertEquals(2, RunQuestions.perceptionDrift.criteria.size)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun drift(
        delta: FieldDelta,
        cycleResults: List<StepOutcome> = listOf(searchOutcome()),
        names: Map<String, String> = NAMES,
    ): RunDrift = RunDrift(
        runId = RUN_ID,
        goal = Task.Step(id = "goal-1", status = TaskStatus.Pending, description = GOAL_TEXT),
        cycle = 2,
        perceived = PERCEIVED,
        delta = delta,
        cycleResults = cycleResults,
        names = names,
    )

    private fun populated(): FieldDelta = FieldDelta(
        fromSequence = 3,
        toSequence = 9,
        goalsChanged = listOf(
            GoalChange(
                goal = Goal(id = ROOT_GOAL, description = GOAL_TEXT, provenance = PROVENANCE),
                from = Field.GoalState.OPEN,
                to = Field.GoalState.COMPLETED,
            ),
        ),
        commitmentsOpened = listOf(
            Commitment(
                committer = ADA,
                ticketId = "AMPR-9",
                due = Instant.parse("2026-10-21T13:00:00Z"),
                status = Commitment.Status.OPEN,
                provenance = PROVENANCE,
            ),
        ),
        commitmentsResolved = emptyList(),
        beliefsAdded = listOf(
            belief(subject = ROOT_GOAL, content = "the uploader retries twice", kind = BeliefKind.LESSON),
            belief(subject = ADA, content = SECRET, kind = BeliefKind.TASK, sensitivity = Sensitivity.PROTECTED),
        ),
        observationsAdded = listOf(observation("the queue is empty", Sensitivity.PUBLIC)),
        actionsByOthers = listOf(action(result = ActionResult.Succeeded, state = ActionState.TAKEN)),
        subjectsAdded = listOf(Subject.Agent(id = Ref("seat-bot"), name = "Bot", provenance = PROVENANCE)),
        relationshipsAdded = emptyList(),
        protectedLeftOut = 1,
    )

    private fun belief(
        subject: Ref<Primitive>,
        content: String,
        kind: BeliefKind,
        sensitivity: Sensitivity = Sensitivity.PUBLIC,
    ): Belief = Belief.Settled(
        holder = HOST,
        subject = subject,
        content = content,
        kind = kind,
        confidence = 0.9,
        sensitivity = sensitivity,
        provenance = PROVENANCE,
    )

    private fun observation(content: String, sensitivity: Sensitivity): Belief.Settled = Belief.Settled(
        holder = HOST,
        subject = Ref<Environment>("canon/link-1/entity-1"),
        content = content,
        kind = BeliefKind.OBSERVATION,
        confidence = 1.0,
        sensitivity = sensitivity,
        provenance = PROVENANCE,
    )

    private fun action(result: ActionResult?, state: ActionState): Action = Action(
        id = ActionId("action-1"),
        actor = BOT,
        description = "posted the summary",
        state = state,
        reversibility = null,
        cost = null,
        result = result,
        provenance = PROVENANCE,
    )

    private fun searchOutcome(): StepOutcome = StepOutcome.Success(
        id = "outcome-1",
        stepDescription = "Search",
        startTimestamp = Instant.fromEpochSeconds(0),
        endTimestamp = Instant.fromEpochSeconds(1),
        details = "found it",
        origin = ContentOrigin.External,
    )

    private companion object {
        const val RUN_ID = "run-ampr-481"
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val PERCEIVED = "the seats are declared"
        const val SECRET = "Ada's home address is 12 Elm Street"
        const val CLIP = 400

        val HOST: Ref<Subject> = Ref("seat-host")
        val BOT: Ref<Subject> = Ref("seat-bot")
        val ADA: Ref<Subject> = Ref("ada")
        val ROOT_GOAL: Ref<Goal> = Ref("goal-1")
        val PROVENANCE = Provenance(createdBy = "event-1", createdAt = Instant.fromEpochSeconds(0))
        val NAMES: Map<String, String> = mapOf(
            "seat-host" to "Host",
            "seat-bot" to "Bot",
            "ada" to "Ada",
            "goal-1" to GOAL_TEXT,
        )

        /** The state of [populated]: every branch filled, the protected belief counted and absent. */
        const val GOLDEN_STATE: String =
            "{\"goal\":\"Add a retry to the uploader\",\"cycle\":2,\"perceived\":\"the seats are declared\"," +
                "\"changes\":{" +
                "\"goals\":[{\"goal\":\"Add a retry to the uploader\",\"from\":\"OPEN\",\"to\":\"COMPLETED\"}]," +
                "\"commitments\":{\"opened\":[{\"by\":\"Ada\",\"on\":\"AMPR-9\",\"due\":\"2026-10-21\"}]," +
                "\"resolved\":[]}," +
                "\"beliefs\":[{\"kind\":\"lesson\",\"holder\":\"Host\",\"about\":\"Add a retry to the uploader\"," +
                "\"content\":\"the uploader retries twice\"}]," +
                "\"observations\":[{\"about\":\"canon/link-1/entity-1\",\"content\":\"the queue is empty\"}]," +
                "\"actions\":[{\"by\":\"Bot\",\"did\":\"posted the summary\",\"result\":\"succeeded\"}]," +
                "\"subjects\":[\"Bot\"]," +
                "\"protected_left_out\":1}," +
                "\"cycle_results\":[{\"step\":\"Search\",\"status\":\"succeeded\",\"origin\":\"external\"," +
                "\"result\":\"found it\"}]}"

        /** SHA-256 of each question's canonical JSON; a reworded question moves it on purpose. */
        const val PERCEPTION_DRIFT_VERSION = "df384d8aab4789445da0b51faa627c1ed0efce0c36292c888553096a3414482c"
        const val LESSON_WORTH_KEEPING_VERSION = "3e89916d5cec1d008b414bda4c04ce1202c7381d6a5ec8f0b1f04a706ff8234d"
    }
}
