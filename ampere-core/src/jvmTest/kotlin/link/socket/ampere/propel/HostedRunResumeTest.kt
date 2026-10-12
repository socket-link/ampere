package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-431 (AMPR-385 rows H29–H32, ruled on 2026-10-11): a held run resumes where it stopped.
 *
 * A run that holds is recorded before `execute()` returns. `RunHost.resume` rebuilds it from
 * that record — on a new host instance if need be, which is the process-death case — and
 * carries on with the plan that was approved: the held step takes the consumer's result, the
 * steps after it run in order, and the run continues exactly as after any cycle. A declined
 * hold ends the run. One hold is resumed once, and Ampere never runs the held tool again: the
 * holding tools below count their dispatches, and every test that resumes asserts one.
 *
 * Driven through the production wiring — real doors onto one store, a real
 * `FunctionExecutor`, the real phase services behind counting fakes — like [HostedRunHeldTest].
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual clock
 * walks straight past it.
 */
class HostedRunResumeTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore
    private lateinit var heldStore: HeldRunStore

    private val searched = CopyOnWriteArrayList<ExecutionRequest<*>>()
    private val filed = CopyOnWriteArrayList<ExecutionRequest<*>>()
    private val archived = CopyOnWriteArrayList<ExecutionRequest<*>>()

    /** Every dispatch of a holding tool. Ampere must never make a second one per hold. */
    private val sent = CopyOnWriteArrayList<ExecutionRequest<*>>()
    private val confirmed = CopyOnWriteArrayList<ExecutionRequest<*>>()

    private val gate = CountingGate()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        store = memoryStoreOf(
            knowledge = KnowledgeRepositoryImpl(door.database, door.driver),
            outcomes = OutcomeMemoryRepositoryImpl(door.database),
        )
        heldStore = InMemoryHeldRunStore()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    // ========================================================================
    // When a run holds
    // ========================================================================

    @Test
    fun `a run that holds with a store is resumable, and its record holds the plan and every result so far`() =
        runBlocking<Unit> {
            val host = hostSeat { planJson(searchStep(), sendStep(), fileStep()) }

            val run = openRun(host)
            val result = run.execute()
            run.close()

            assertEquals(RunStatus.Held, result.status)
            assertTrue(result.resumable, "the record was committed")

            val record = assertNotNull(heldStore.get(run.runId).getOrThrow())
            assertEquals(result.stepOutcomes, record.results, "every result so far, the skip after the hold too")
            assertEquals(3, record.plan.tasks.size, "the whole gated plan")
            assertEquals(result.stepOutcomes[1].id, record.heldStepId)
            assertEquals(REASON, record.reason)
            assertEquals(REFERENCE, record.reference)
            assertEquals(1, record.cycle)
            assertEquals(HOST_ROLE.value, record.hostRole)
            assertEquals(HOST_ID, record.hostAgentId)
            assertEquals(GOAL_ID, record.goal.id)
            assertTrue(record.ideas.isNotEmpty(), "the host seat's ideas, which later cycles plan from")
            assertEquals(setOf(SCOUT_ROLE.value, CLERK_ROLE.value), record.seatsThatRan)
        }

    @Test
    fun `a run that holds with no store is not resumable`() = runBlocking<Unit> {
        val host = hostSeat { planJson(sendStep()) }
        val runHost = runHost(heldRuns = null)

        val run = openRun(host, runHost = runHost)
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Held, result.status)
        assertFalse(result.resumable)
        assertTrue(runHost.heldRuns().isEmpty())
        val refused = assertIs<HeldRunUnavailableException>(
            refusal {
                resume(runHost, run.runId, host, HoldResolution.Completed(HELD_RESULT))
            },
        )
        assertEquals(HeldRunUnavailableException.Why.NoStore, refused.why)
    }

    @Test
    fun `a store that fails to write leaves the run Held and not resumable`() = runBlocking<Unit> {
        val failing = object : HeldRunStore by InMemoryHeldRunStore() {
            override suspend fun hold(run: HeldRun): Result<Unit> =
                Result.failure(IllegalStateException("the disk is full"))
        }
        val host = hostSeat { planJson(sendStep()) }

        val run = openRun(host, runHost = runHost(heldRuns = failing))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Held, result.status, "the hold happened, recorded or not")
        assertFalse(result.resumable)
        assertTrue(failing.list().getOrThrow().isEmpty())
    }

    // ========================================================================
    // resume with Completed
    // ========================================================================

    @Test
    fun `resume with Completed runs the steps after the held one in order, the first handed the held result`() =
        runBlocking<Unit> {
            val host = hostSeat { planJson(searchStep(), sendStep(), fileStep(), archiveStep()) }
            val runHost = runHost()
            val held = openRun(host, runHost = runHost)
            val heldResult = held.execute()
            held.close()
            val heldStepId = heldResult.stepOutcomes[1].id

            val resumed = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
            assertEquals(
                heldResult.stepOutcomes.drop(2).map { it.id },
                resumed.plan.tasks.map { it.id },
                "the plan is the steps after the held one",
            )
            val result = resumed.execute()
            resumed.close()

            assertEquals(RunStatus.Succeeded, result.status)
            assertEquals(1, result.cyclesUsed)
            assertEquals(
                heldResult.stepOutcomes.map { it.id },
                result.stepOutcomes.map { it.id },
                "one run, start to end",
            )
            assertTrue(result.stepOutcomes.all { it is StepOutcome.Success })
            val settled = assertIs<StepOutcome.Success>(result.stepOutcomes[1])
            assertEquals(HELD_RESULT, settled.details, "the held step's result is the consumer's report")

            assertEquals(1, sent.size, "the held tool ran once, and was never run again")
            val handedOn = assertIs<StepOutcome.Success>(filed.single().priorResults.last())
            assertEquals(heldStepId, handedOn.id)
            assertEquals(HELD_RESULT, handedOn.details, "the first remaining step was handed the held action's result")
            assertEquals(1, archived.size)

            val started = events(held.runId).filterIsInstance<PlanEvent.PlanStepStarted>()
            assertEquals(
                heldResult.stepOutcomes.map { it.id },
                started.map { it.stepId },
                "in the approved order; the held step's start is the one from before the hold",
            )
            assertEquals(listOf(0, 1, 2, 3), started.map { it.stepIndex }, "indexed as the approved plan")
            assertTrue(started.all { it.totalSteps == 4 })

            val heldCompletions = events(held.runId).filterIsInstance<PlanEvent.PlanStepCompleted>()
                .filter { it.stepId == heldStepId }
            assertIs<StepOutcome.Held>(heldCompletions.first().outcome)
            val completed = assertIs<StepOutcome.Success>(heldCompletions.last().outcome)
            assertEquals(HELD_RESULT, completed.details)
            assertEquals(CLERK_ID, heldCompletions.last().eventSource.getIdentifier(), "through its own seat's door")
        }

    @Test
    fun `resume makes no PERCEIVE call, no PLAN call for the held cycle, and does not call the plan gate`() =
        runBlocking<Unit> {
            val host = hostSeat { planJson(searchStep(), sendStep(), fileStep()) }
            val runHost = runHost()
            val held = openRun(host, runHost = runHost)
            held.execute()
            held.close()
            val callsBefore = host.transport.callCount
            val reviewsBefore = gate.reviews.get()
            val since = latestSequence()

            val resumed = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
            resumed.execute()
            resumed.close()

            assertEquals(callsBefore, host.transport.callCount, "no model call on the host seat at all")
            assertEquals(1, host.perceiveCalls())
            assertEquals(1, host.planCalls())
            assertEquals(reviewsBefore, gate.reviews.get(), "the plan was approved once, before the hold")
            assertEquals(0, gate.changedReviews.get())

            val hostPhases = rowsSince(since)
                .filter { it.source_id == HOST_ID }
                .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
                .map { it.newPhase }
            assertFalse(CognitivePhase.PERCEIVE in hostPhases, "entered: $hostPhases")
            assertFalse(CognitivePhase.PLAN in hostPhases, "entered: $hostPhases")
            assertTrue(
                hostPhases.indexOf(CognitivePhase.RECALL) in 0 until hostPhases.indexOf(CognitivePhase.OBSERVE),
                "RECALL again, then OBSERVE: $hostPhases",
            )
        }

    @Test
    fun `every event of a resumed run carries the original run id`() = runBlocking<Unit> {
        val host = hostSeat { planJson(searchStep(), sendStep(), fileStep()) }
        val runHost = runHost()
        val held = openRun(host, runHost = runHost)
        held.execute()
        held.close()
        val since = latestSequence()

        val resumed = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
        assertEquals(held.runId, resumed.runId)
        val result = resumed.execute()
        resumed.close()
        assertEquals(held.runId, result.runId)

        val seatRows = rowsSince(since).filter { it.source_id in setOf(HOST_ID, SCOUT_ID, CLERK_ID) }
        assertTrue(seatRows.isNotEmpty(), "the resumed run published something")
        val strays = seatRows.filter { it.run_id != held.runId }
        assertTrue(strays.isEmpty(), "every envelope carries the run id; these do not: ${strays.map { it.event_type }}")
        assertEquals(held.runId, filed.single().runId, "and the tool was dispatched under it")
    }

    @Test
    fun `a resume on a second host instance over the same store works`() = runBlocking<Unit> {
        val host = hostSeat { planJson(searchStep(), sendStep(), fileStep()) }

        // Each host its own store object over one database: nothing is shared but the file.
        val first = runHost(heldRuns = SqlDelightHeldRunStore(door.database))
        val held = openRun(host, runHost = first)
        val heldResult = held.execute()
        held.close()
        assertTrue(heldResult.resumable)

        val second = runHost(heldRuns = SqlDelightHeldRunStore(door.database))
        assertEquals(listOf(held.runId), second.heldRuns().map { it.runId }, "the new host sees the hold")

        val resumed = resume(second, held.runId, host, HoldResolution.Completed(HELD_RESULT))
        val result = resumed.execute()
        resumed.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(1, sent.size, "the held tool ran once, in the first host")
        assertEquals(1, filed.size, "and the step after it ran in the second")
        assertTrue(second.heldRuns().isEmpty(), "the finished run's record is gone")
    }

    @Test
    fun `the second of two resumes throws AlreadyClaimed and publishes nothing`() = runBlocking<Unit> {
        val host = hostSeat { planJson(sendStep(), fileStep()) }
        val runHost = runHost()
        val held = openRun(host, runHost = runHost)
        held.execute()
        held.close()

        resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))

        // The second resumer is another host — the first still has the run open — with seats
        // of its own, so anything it published would be told apart by who published it.
        val since = latestSequence()
        val otherHost = hostSeat(id = OTHER_HOST_ID) { planJson(sendStep(), fileStep()) }
        val refused = assertIs<HeldRunUnavailableException>(
            refusal {
                runHost().resume(
                    runId = held.runId,
                    roster = roster(),
                    seats = mapOf(
                        HOST_ROLE to otherHost.descriptor,
                        CLERK_ROLE to seat(OTHER_CLERK_ID, CLERK_ROLE).descriptor,
                    ),
                    resolution = HoldResolution.Completed("a second report"),
                    tools = tools(),
                )
            },
        )
        assertEquals(HeldRunUnavailableException.Why.AlreadyClaimed, refused.why)
        assertTrue(
            rowsSince(since).none { it.source_id in setOf(OTHER_HOST_ID, OTHER_CLERK_ID) },
            "the losing resume built no seat that published anything",
        )
        assertEquals(0, otherHost.transport.callCount, "and called no model")
        assertNotNull(heldStore.list().getOrThrow().single().claimedAt, "the record stays claimed by the first")
    }

    @Test
    fun `resume of a run that is still open throws StillOpen`() = runBlocking<Unit> {
        val host = hostSeat { planJson(sendStep(), fileStep()) }
        val runHost = runHost()
        val held = openRun(host, runHost = runHost)
        assertTrue(held.execute().resumable)

        val refused = assertIs<HeldRunUnavailableException>(
            refusal {
                resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
            },
        )
        assertEquals(HeldRunUnavailableException.Why.StillOpen, refused.why)
        assertNull(heldStore.list().getOrThrow().single().claimedAt, "a refused resume claims nothing")

        held.close()
        val resumed = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
        assertEquals(RunStatus.Succeeded, resumed.execute().status, "and once it is closed, it resumes")
        resumed.close()
    }

    @Test
    fun `resume of an unknown run throws NotHeld`() = runBlocking<Unit> {
        val host = hostSeat { planJson(sendStep()) }

        val refused = assertIs<HeldRunUnavailableException>(
            refusal {
                resume(runHost(), "no-such-run", host, HoldResolution.Completed(HELD_RESULT))
            },
        )

        assertEquals(HeldRunUnavailableException.Why.NotHeld, refused.why)
        assertEquals("no-such-run", refused.runId)
        assertTrue(rows("no-such-run").isEmpty(), "nothing was published under it")
        assertEquals(0, host.transport.callCount)
    }

    @Test
    fun `a roster whose host is not the held run's is refused before the claim`() = runBlocking<Unit> {
        val host = hostSeat { planJson(sendStep()) }
        val runHost = runHost()
        val held = openRun(host, runHost = runHost)
        held.execute()
        held.close()

        val otherRoster = RosterConfig(host = CLERK_ROLE, roles = roster().all().toList())
        assertIs<IllegalArgumentException>(
            refusal {
                runHost.resume(
                    runId = held.runId,
                    roster = otherRoster,
                    seats = seats(host),
                    resolution = HoldResolution.Completed(HELD_RESULT),
                    tools = tools(),
                )
            },
        )
        assertNull(heldStore.list().getOrThrow().single().claimedAt, "the record is left for a roster that fits")
    }

    @Test
    fun `a resumed run that holds again is resumable again, and its second resume continues after that step`() =
        runBlocking<Unit> {
            val host = hostSeat { planJson(searchStep(), sendStep(), fileStep(), confirmStep(), archiveStep()) }
            val runHost = runHost()
            val held = openRun(host, runHost = runHost)
            held.execute()
            held.close()

            val once = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
            val second = once.execute()
            once.close()

            assertEquals(RunStatus.Held, second.status)
            assertTrue(second.resumable, "a new record, resumable again")
            val record = assertNotNull(heldStore.get(held.runId).getOrThrow())
            assertEquals(second.stepOutcomes[3].id, record.heldStepId, "held on the second holding step")
            assertEquals(5, record.plan.tasks.size, "against the whole approved plan")
            assertNull(heldStore.list().getOrThrow().single().claimedAt, "and unclaimed")

            val twice = resume(runHost, held.runId, host, HoldResolution.Completed(CONFIRM_RESULT))
            assertEquals(listOf(second.stepOutcomes[4].id), twice.plan.tasks.map { it.id })
            val result = twice.execute()
            twice.close()

            assertEquals(RunStatus.Succeeded, result.status)
            assertEquals(5, result.stepOutcomes.size)
            assertTrue(result.stepOutcomes.all { it is StepOutcome.Success })
            assertEquals(1, sent.size)
            assertEquals(1, confirmed.size, "each holding tool ran once")
            assertEquals(CONFIRM_RESULT, assertIs<StepOutcome.Success>(archived.single().priorResults.last()).details)

            val archiveStarted = events(held.runId).filterIsInstance<PlanEvent.PlanStepStarted>().last()
            assertEquals(4, archiveStarted.stepIndex)
            assertEquals(5, archiveStarted.totalSteps)
            assertTrue(heldStore.list().getOrThrow().isEmpty())
        }

    @Test
    fun `a resumed run with cycles left plans the next cycle with every result so far`() = runBlocking<Unit> {
        val plans = AtomicInteger(0)
        val host = hostSeat {
            if (plans.getAndIncrement() == 0) planJson(searchStep(), sendStep()) else planJson(fileStep())
        }
        val runHost = runHost()
        val policy = RunPolicy(cycles = 2, planGate = gate)
        val held = openRun(host, runHost = runHost, policy = policy)
        held.execute()
        held.close()

        val resumed = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT), policy = policy)
        val result = resumed.execute()
        resumed.close()

        assertEquals(2, host.planCalls(), "the held cycle's, and the next one's")
        assertEquals(2, result.cyclesUsed)
        val nextPlan = host.transport.prompts.last { it.contains("\"steps\"") }
        assertTrue(nextPlan.contains(SEARCH_RESULT), "the next PLAN sees the step before the hold")
        assertTrue(nextPlan.contains(HELD_RESULT), "and the held action's result")
        assertEquals(1, filed.size, "and the next cycle ran")
        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(2, gate.reviews.get(), "the next cycle's plan went to the gate; the held one's did not again")
    }

    // ========================================================================
    // resume with Declined
    // ========================================================================

    @Test
    fun `resume with Declined runs and plans nothing, ends Failed, records the outcome and publishes TaskFailed`() =
        runBlocking<Unit> {
            val host = hostSeat { planJson(searchStep(), sendStep(), fileStep()) }
            val runHost = runHost()
            val policy = RunPolicy(cycles = 3, planGate = gate)
            val held = openRun(host, runHost = runHost, policy = policy)
            held.execute()
            held.close()
            val callsBefore = host.transport.callCount

            val resumed = resume(runHost, held.runId, host, HoldResolution.Declined(DECLINE_REASON), policy = policy)
            assertTrue(resumed.plan.tasks.isEmpty())
            val result = resumed.execute()
            resumed.close()

            assertEquals(RunStatus.Failed, result.status, "a run with a success before the hold is still Failed")
            assertIs<StepOutcome.Success>(result.stepOutcomes[0])
            val declined = assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
            assertTrue(declined.error.startsWith("Declined:"), declined.error)
            assertTrue(declined.error.contains(DECLINE_REASON))
            assertTrue(declined.isCritical)
            assertIs<StepOutcome.Skipped>(result.stepOutcomes[2], "the skip after it stays")
            assertTrue(filed.isEmpty(), "nothing after the held step ran")
            assertEquals(1, sent.size)
            assertEquals(callsBefore, host.transport.callCount, "nothing was perceived or planned")
            assertEquals(1, result.cyclesUsed)

            val completed = events(held.runId).filterIsInstance<PlanEvent.PlanStepCompleted>()
                .last { it.stepId == declined.id }
            assertIs<StepOutcome.Failure>(completed.outcome, "the held step's completion went out")

            val outcome = store.outcomes.getOutcomesByTicket(held.runId).getOrThrow().single()
            assertFalse(outcome.success, "the outcome is recorded, failed")
            val runTask = events(held.runId).filterIsInstance<TaskEvent>().filter { it.taskId == held.runId }
            assertEquals(1, runTask.count { it is TaskEvent.TaskFailed }, "the run's task ended failed")
            assertTrue(runTask.none { it is TaskEvent.TaskCompleted })
            assertNull(heldStore.get(held.runId).getOrThrow(), "and the record is deleted")
        }

    // ========================================================================
    // The run's task, its record, and LEARN
    // ========================================================================

    @Test
    fun `the run's task across a hold and a resume has one Created, Started, Blocked, Progressed and terminal event`() =
        runBlocking<Unit> {
            val host = hostSeat { planJson(searchStep(), sendStep(), fileStep()) }
            val runHost = runHost()
            val held = openRun(host, runHost = runHost)
            held.execute()
            held.close()

            val resumed = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
            resumed.execute()
            resumed.close()

            val task = events(held.runId).filter { (it as? TaskEvent)?.taskId == held.runId || it is Event.TaskCreated }
            assertEquals(1, task.filterIsInstance<Event.TaskCreated>().count { it.taskId == held.runId })
            assertEquals(1, task.count { it is TaskEvent.TaskStarted })
            assertEquals(1, task.count { it is TaskEvent.TaskBlocked })
            assertEquals(1, task.count { it is TaskEvent.TaskProgressed })
            assertEquals(1, task.count { it is TaskEvent.TaskCompleted || it is TaskEvent.TaskFailed })
            assertIs<TaskEvent.TaskCompleted>(task.last())
        }

    @Test
    fun `a finished run's record is gone and a held run's record stays`() = runBlocking<Unit> {
        val host = hostSeat { planJson(sendStep(), fileStep()) }
        val runHost = runHost()
        val held = openRun(host, runHost = runHost)
        held.execute()
        held.close()

        val listed = runHost.heldRuns().single()
        assertEquals(held.runId, listed.runId, "closing a held run keeps its record")
        assertEquals(REASON, listed.reason)
        assertNull(listed.claimedAt)

        val resumed = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
        assertNotNull(runHost.heldRuns().single().claimedAt, "claimed while the resumed run is open")
        resumed.execute()
        resumed.close()

        assertTrue(runHost.heldRuns().isEmpty(), "and gone once it finished")
    }

    @Test
    fun `a run that never resumed leaves an earlier run's record under the same id alone`() = runBlocking<Unit> {
        val host = hostSeat { planJson(sendStep()) }
        val runHost = runHost()
        val held = openRun(host, runHost = runHost, runId = SHARED_RUN_ID)
        held.execute()
        held.close()

        val other = hostSeat { planJson(fileStep()) }
        val again = openRun(other, runHost = runHost, runId = SHARED_RUN_ID)
        assertEquals(RunStatus.Succeeded, again.execute().status)
        again.close()

        assertEquals(listOf(SHARED_RUN_ID), runHost.heldRuns().map { it.runId })
    }

    @Test
    fun `LEARN after a resumed run stores Knowledge for a seat that ran only before the hold`() = runBlocking<Unit> {
        // The held step is the plan's last: the resumed cycle has no steps left to walk.
        val host = hostSeat { planJson(searchStep(), sendStep()) }
        val runHost = runHost()
        val held = openRun(host, runHost = runHost)
        held.execute()
        held.close()
        val since = latestSequence()

        val resumed = resume(runHost, held.runId, host, HoldResolution.Completed(HELD_RESULT))
        assertTrue(resumed.plan.tasks.isEmpty())
        val result = resumed.execute()
        resumed.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertIs<ExecutionOutcome.NoChanges.Success>(result.outcome, "the held cycle, folded whole")
        val storedBy = rowsSince(since)
            .mapNotNull { decode(it) as? MemoryEvent.KnowledgeStored }
            .mapTo(mutableSetOf()) { it.eventSource.getIdentifier() }
        assertTrue(SCOUT_ID in storedBy, "the scout ran only before the hold, and learns: $storedBy")
        assertTrue(CLERK_ID in storedBy, "and so does the seat that held: $storedBy")
        assertEquals(1, store.outcomes.getOutcomesByTicket(held.runId).getOrThrow().size, "one outcome row")
    }

    @Test
    fun `a remaining step whose tool its seat no longer has fails that step and skips the rest`() =
        runBlocking<Unit> {
            val host =
                hostSeat { planJson(searchStep(), sendStep(), fileStep(), archiveStep(dependsOnPrevious = true)) }
            val runHost = runHost()
            val held = openRun(host, runHost = runHost)
            held.execute()
            held.close()

            val resumed = resume(
                runHost,
                held.runId,
                host,
                HoldResolution.Completed(HELD_RESULT),
                tools = tools() - fileTool,
            )
            val result = resumed.execute()
            resumed.close()

            val fileOutcome = assertIs<StepOutcome.Failure>(result.stepOutcomes[2])
            val archiveOutcome = assertIs<StepOutcome.Skipped>(result.stepOutcomes[3])
            assertEquals("depends on failed step ${fileOutcome.id}", archiveOutcome.reason)
            assertTrue(filed.isEmpty())
            assertTrue(archived.isEmpty())
            assertEquals(RunStatus.Partial, result.status)
            assertEquals(1, sent.size)
        }

    // ========================================================================
    // Fixture
    // ========================================================================

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    ) {
        fun planCalls(): Int = transport.prompts.count { it.contains("\"steps\"") }
        fun perceiveCalls(): Int = transport.prompts.count { it.contains("perception module") }
    }

    /** A plan gate that approves everything and counts what it was asked. */
    private class CountingGate : PlanGate {
        val reviews = AtomicInteger(0)
        val changedReviews = AtomicInteger(0)

        override suspend fun review(plan: Plan): PlanDecision {
            reviews.incrementAndGet()
            return PlanDecision.Approved(plan)
        }

        override suspend fun reviewChangedStep(
            step: Task.Step,
            approved: JsonElement?,
            actual: JsonElement,
        ): PlanDecision {
            changedReviews.incrementAndGet()
            return super.reviewChangedStep(step, approved, actual)
        }
    }

    private val scout by lazy { seat(SCOUT_ID, SCOUT_ROLE) }
    private val clerk by lazy { seat(CLERK_ID, CLERK_ROLE) }

    private val searchTool by lazy { recordingTool(SEARCH_TOOL, SEARCH_RESULT, searched) }
    private val fileTool by lazy { recordingTool(FILE_TOOL, FILED_RESULT, filed) }
    private val archiveTool by lazy { recordingTool(ARCHIVE_TOOL, "archived", archived) }
    private val sendTool by lazy { holdingTool(SEND_TOOL, REASON, REFERENCE, sent) }
    private val confirmTool by lazy { holdingTool(CONFIRM_TOOL, "waiting for a second approval", null, confirmed) }

    private fun tools(): Set<Tool<*>> = setOf(searchTool, fileTool, archiveTool, sendTool, confirmTool)

    private fun hostSeat(id: String = HOST_ID, answering: () -> String): Seat =
        seat(id, HOST_ROLE, hostAnswers(answering))

    private fun seat(
        id: String,
        role: RoleId,
        answer: (String) -> String = fixedAnswer { "unused" },
    ): Seat {
        val transport = CountingUpstreamLlmClient(answer)
        return Seat(
            descriptor = HostedAgent(
                id = id,
                role = role,
                charter = "You are the ${role.value} of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
                memory = store,
            ),
            transport = transport,
        )
    }

    private fun seats(host: Seat): Map<RoleId, HostedAgent> = mapOf(
        HOST_ROLE to host.descriptor,
        SCOUT_ROLE to scout.descriptor,
        CLERK_ROLE to clerk.descriptor,
    )

    private fun roster(): Roster = RosterConfig(
        host = HOST_ROLE,
        roles = listOf(
            RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
            RoleConfig(id = SCOUT_ROLE, title = "Scout", instructions = PROMPT, tools = setOf(SEARCH_TOOL)),
            RoleConfig(
                id = CLERK_ROLE,
                title = "Clerk",
                instructions = PROMPT,
                tools = setOf(SEND_TOOL, FILE_TOOL, ARCHIVE_TOOL, CONFIRM_TOOL),
            ),
        ),
    )

    private fun runHost(heldRuns: HeldRunStore? = heldStore): RunHost =
        rosterRunHost(createEventApi = door::doorFor, agentScope = scope, heldRuns = heldRuns)

    private suspend fun openRun(
        host: Seat,
        runHost: RunHost = runHost(),
        policy: RunPolicy = RunPolicy(planGate = gate),
        runId: String? = null,
    ): HostedRun = runHost.open(
        roster = roster(),
        seats = seats(host),
        goal = goal(GOAL_TEXT, id = GOAL_ID),
        tools = tools(),
        policy = policy,
        runId = runId,
    )

    private suspend fun resume(
        runHost: RunHost,
        runId: String,
        host: Seat,
        resolution: HoldResolution,
        policy: RunPolicy = RunPolicy(planGate = gate),
        tools: Set<Tool<*>> = tools(),
    ): HostedRun = runHost.resume(
        runId = runId,
        roster = roster(),
        seats = seats(host),
        resolution = resolution,
        tools = tools,
        policy = policy,
    )

    /** A tool whose action waits on a person: it records its request and holds. */
    private fun holdingTool(
        id: String,
        reason: String,
        reference: String?,
        dispatched: MutableList<ExecutionRequest<*>>,
    ): FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
        id = id,
        name = "Holding $id",
        description = "acts on a person's behalf once they approve",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            dispatched += request
            val now = Clock.System.now()
            ExecutionOutcome.Held(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                reason = reason,
                reference = reference,
            )
        },
    )

    private fun searchStep(): String = planStep(
        "Search the archive",
        SeatTool(SCOUT_ROLE, SEARCH_TOOL).id,
        SCOUT_ROLE.value,
    )

    private fun sendStep(): String = planStep("Send the reply", clerkTool(SEND_TOOL), CLERK_ROLE.value)

    private fun fileStep(): String = planStep("File the thread", clerkTool(FILE_TOOL), CLERK_ROLE.value)

    private fun confirmStep(): String = planStep("Confirm the booking", clerkTool(CONFIRM_TOOL), CLERK_ROLE.value)

    private fun archiveStep(dependsOnPrevious: Boolean = false): String =
        planStep("Archive the thread", clerkTool(ARCHIVE_TOOL), CLERK_ROLE.value)
            .replace("\"requiresPreviousStep\": false", "\"requiresPreviousStep\": $dependsOnPrevious")

    private fun clerkTool(tool: String): String = SeatTool(CLERK_ROLE, tool).id

    /** What [block] threw, or null. `assertFailsWith`'s block cannot suspend. */
    private suspend fun refusal(block: suspend () -> Unit): Throwable? =
        runCatching { block() }.exceptionOrNull()

    private fun latestSequence(): Long = door.database.eventStoreQueries.latestSequence().executeAsOne()

    private fun rowsSince(sequence: Long): List<EventStore> =
        door.database.eventStoreQueries.getEventsSinceSequence(sequence + 1).executeAsList()

    private fun rows(runId: String): List<EventStore> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList()

    private fun events(runId: String): List<Event> = rows(runId).map(::decode)

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    private companion object {
        const val SDK_ID = "hosted-run-resume-test-sdk"
        const val HOST_ID = "seat-host"
        const val SCOUT_ID = "seat-scout"
        const val CLERK_ID = "seat-clerk"
        const val OTHER_HOST_ID = "seat-host-elsewhere"
        const val OTHER_CLERK_ID = "seat-clerk-elsewhere"
        const val SEARCH_TOOL = "search"
        const val SEND_TOOL = "send_reply"
        const val FILE_TOOL = "file_thread"
        const val ARCHIVE_TOOL = "archive_thread"
        const val CONFIRM_TOOL = "confirm_booking"
        const val SEARCH_RESULT = "found three earlier messages"
        const val FILED_RESULT = "filed under replies"
        const val HELD_RESULT = "sent at 09:14, message id m-881"
        const val CONFIRM_RESULT = "booking confirmed, reference b-42"
        const val DECLINE_REASON = "the account owner said not to send it"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-431"
        const val SHARED_RUN_ID = "run-ampr-431-shared"
        const val REASON = "waiting for the account owner to approve sending"
        const val REFERENCE = "approval-request-17"

        val HOST_ROLE = RoleId("planner")
        val SCOUT_ROLE = RoleId("scout")
        val CLERK_ROLE = RoleId("clerk")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
