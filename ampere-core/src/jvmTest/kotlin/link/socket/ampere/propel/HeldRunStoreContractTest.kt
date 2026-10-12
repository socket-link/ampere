package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.EffortLevel
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.agents.domain.task.Task

/**
 * The contract every [HeldRunStore] keeps (AMPR-430), run against [SqlDelightHeldRunStore] by
 * [HeldRunStoreSqlDelightTest] and against [InMemoryHeldRunStore] by [HeldRunStoreInMemoryTest].
 *
 * Bodies use `runBlocking`: the SQL store commits on a real IO dispatcher, and `runTest`'s virtual
 * clock would run ahead of it. Every instant is whole milliseconds, which is what the SQL store's
 * scalar columns keep.
 */
abstract class HeldRunStoreContractTest {

    /** A fresh, empty store. Called once per test. */
    protected abstract fun newStore(): HeldRunStore

    /**
     * Make the stored payload of [runId] unreadable to this build, by hand, leaving its scalar
     * columns alone. A store that keeps no payload has nothing to corrupt and does nothing.
     */
    protected abstract fun corruptPayload(runId: String)

    @Test
    fun `a held run reads back equal`() = runBlocking<Unit> {
        val store = newStore()
        val run = heldRun()

        store.hold(run).getOrThrow()

        val read = store.get(run.runId).getOrThrow()
        assertEquals(run, read)
        assertEquals(run.plan.id, read?.plan?.id, "the plan id is a body property, outside data-class equals")
    }

    @Test
    fun `list reports claimed and unclaimed records without decoding`() = runBlocking<Unit> {
        val store = newStore()
        val oldest = heldRun(runId = "run-oldest", heldAt = heldAt)
        val middle = heldRun(runId = "run-middle", heldAt = heldAt + 1.minutes, reference = null)
        val newest = heldRun(runId = "run-newest", heldAt = heldAt + 2.minutes)
        listOf(newest, oldest, middle).forEach { store.hold(it).getOrThrow() }
        store.claim(middle.runId, claimedAt).getOrThrow()
        corruptPayload(newest.runId)

        assertEquals(
            listOf(
                summaryOf(oldest, claimedAt = null),
                summaryOf(middle, claimedAt = claimedAt),
                summaryOf(newest, claimedAt = null),
            ),
            store.list().getOrThrow(),
        )
    }

    @Test
    fun `of twenty concurrent claims exactly one is Claimed and the rest AlreadyClaimed`() = runBlocking<Unit> {
        val store = newStore()
        val run = heldRun()
        store.hold(run).getOrThrow()

        val claims = coroutineScope {
            (0 until 20).map { claimer ->
                async(Dispatchers.Default) {
                    claimer to store.claim(run.runId, claimedAt + claimer.minutes).getOrThrow()
                }
            }.awaitAll()
        }

        val winners = claims.filter { (_, claim) -> claim is HeldRunClaim.Claimed }
        assertEquals(1, winners.size, "claims: $claims")
        val (winner, won) = winners.single()
        assertEquals(run, assertIs<HeldRunClaim.Claimed>(won).run)

        val losers = claims.map { it.second }.filterNot { it is HeldRunClaim.Claimed }
        assertEquals(19, losers.size)
        losers.forEach { loser ->
            assertEquals(
                claimedAt + winner.minutes,
                assertIs<HeldRunClaim.AlreadyClaimed>(loser).at,
                "a loser reports the claim that won, not its own",
            )
        }
        assertEquals(claimedAt + winner.minutes, store.list().getOrThrow().single().claimedAt)
    }

    @Test
    fun `claiming a run that was never held is NotHeld`() = runBlocking<Unit> {
        val store = newStore()
        store.hold(heldRun(runId = "run-held")).getOrThrow()

        assertEquals(HeldRunClaim.NotHeld, store.claim("run-never-held", claimedAt).getOrThrow())
    }

    @Test
    fun `an identical re-hold of an unclaimed record succeeds and a different one fails`() = runBlocking<Unit> {
        val store = newStore()
        val run = heldRun()
        store.hold(run).getOrThrow()

        assertTrue(store.hold(run).isSuccess, "a retry of a write that already landed is the same write")

        val different = run.copy(reason = "Waiting on a different person")
        val refused = assertIs<HeldRunInUseException>(store.hold(different).exceptionOrNull())
        assertEquals(run.runId, refused.runId)
        assertEquals(run, store.get(run.runId).getOrThrow(), "a refused hold leaves the record alone")
    }

    @Test
    fun `holding again over a claimed record replaces it and clears the claim`() = runBlocking<Unit> {
        val store = newStore()
        val first = heldRun()
        store.hold(first).getOrThrow()
        store.claim(first.runId, claimedAt).getOrThrow()

        val again = first.copy(
            cycle = 2,
            heldStepId = "c2-step-1-goal-1",
            reason = "Waiting on a second approval",
            reference = "approval-2",
            heldAt = claimedAt + 5.minutes,
        )
        store.hold(again).getOrThrow()

        assertEquals(again, store.get(first.runId).getOrThrow())
        assertEquals(listOf(summaryOf(again, claimedAt = null)), store.list().getOrThrow())
        assertEquals(
            HeldRunClaim.Claimed(again),
            store.claim(first.runId, claimedAt + 6.minutes).getOrThrow(),
        )
    }

    @Test
    fun `delete removes the record and is a no-op when there is none`() = runBlocking<Unit> {
        val store = newStore()
        val run = heldRun()
        store.hold(run).getOrThrow()

        store.delete(run.runId).getOrThrow()

        assertNull(store.get(run.runId).getOrThrow())
        assertEquals(emptyList<HeldRunSummary>(), store.list().getOrThrow())
        assertEquals(HeldRunClaim.NotHeld, store.claim(run.runId, claimedAt).getOrThrow())
        assertTrue(store.delete(run.runId).isSuccess)
        assertTrue(store.delete("run-never-held").isSuccess)
    }

    @Test
    fun `a HeldRun round-trips through the store Json with every StepOutcome variant`() {
        val run = heldRun()
        val json = SqlDelightHeldRunStore.DEFAULT_JSON

        val encoded = json.encodeToString(HeldRun.serializer(), run)
        val decoded = json.decodeFromString(HeldRun.serializer(), encoded)

        assertEquals(run, decoded)
        assertEquals(run.plan.id, decoded.plan.id)
        // Exhaustive over StepOutcome: a new variant fails to compile here until the fixture has one.
        val variants = decoded.results.map { outcome ->
            when (outcome) {
                is StepOutcome.Success -> "success"
                is StepOutcome.PartialSuccess -> "partial"
                is StepOutcome.Failure -> "failure"
                is StepOutcome.Held -> "held"
                is StepOutcome.Skipped -> "skipped"
            }
        }
        assertEquals(setOf("success", "partial", "failure", "held", "skipped"), variants.toSet())
        val steps = decoded.plan.tasks.map { assertIs<Task.Step>(it) }
        assertEquals(2, steps.size)
        assertEquals(inlineArguments, steps[0].arguments)
        assertEquals(AssignedTo.Agent("seat-scout"), steps[1].assignedTo)
    }

    protected val heldAt: Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)
    protected val claimedAt: Instant = heldAt + 30.minutes

    private val inlineArguments = buildJsonObject {
        put("calendar", JsonPrimitive("team"))
        put("days", JsonPrimitive(7))
    }

    protected fun heldRun(
        runId: String = "run-1",
        heldAt: Instant = this.heldAt,
        reference: String? = "approval-1",
    ): HeldRun {
        val goal = Task.Step(id = "goal-1", status = TaskStatus.Pending, description = "Book the offsite")
        val readStep = Task.Step(
            id = "step-1-goal-1",
            status = TaskStatus.Pending,
            description = "Read the team calendar",
            toolId = "planner/read_calendar",
            arguments = inlineArguments,
        )
        val bookStep = Task.Step(
            id = "step-2-goal-1",
            status = TaskStatus.Pending,
            description = "Book the venue",
            toolId = "scout/book_venue",
            assignedTo = AssignedTo.Agent("seat-scout"),
            execution = ExecutionAssignment(model = "model-a", effort = EffortLevel.HIGH),
        )
        val start = heldAt - 10.minutes
        return HeldRun(
            runId = runId,
            hostRole = "planner",
            hostAgentId = "seat-planner",
            goal = goal,
            cycle = 2,
            plan = Plan.ForTask(task = goal, tasks = listOf(readStep, bookStep), estimatedComplexity = 3),
            results = listOf(
                StepOutcome.Success("o-1", "Search venues", start, start + 1.minutes, details = "three venues"),
                StepOutcome.PartialSuccess(
                    "o-2",
                    "Ask the venues",
                    start,
                    start + 2.minutes,
                    successCount = 2,
                    failureCount = 1,
                    details = "one did not reply",
                ),
                StepOutcome.Failure(
                    "o-3",
                    "Check the budget",
                    start,
                    start + 3.minutes,
                    error = "budget sheet missing",
                    isCritical = false,
                ),
                StepOutcome.Held(
                    "o-4",
                    "Book the venue",
                    start,
                    start + 4.minutes,
                    reason = "Waiting on approval",
                    reference = reference,
                ),
                StepOutcome.Skipped("o-5", "Send the invites", start + 4.minutes, reason = "Skipped: step 2 is held"),
            ),
            heldStepId = bookStep.id,
            reason = "Waiting on approval",
            reference = reference,
            ideas = listOf(Idea(name = "offsite", id = "idea-1", description = "an offsite next month")),
            seatsThatRan = setOf("planner", "scout"),
            heldAt = heldAt,
        )
    }

    private fun summaryOf(run: HeldRun, claimedAt: Instant?): HeldRunSummary = HeldRunSummary(
        runId = run.runId,
        heldStepId = run.heldStepId,
        reason = run.reason,
        reference = run.reference,
        heldAt = run.heldAt,
        claimedAt = claimedAt,
    )
}

class HeldRunStoreInMemoryTest : HeldRunStoreContractTest() {

    override fun newStore(): HeldRunStore = InMemoryHeldRunStore()

    /** It keeps the decoded record, so there is no payload to corrupt. */
    override fun corruptPayload(runId: String) = Unit
}
