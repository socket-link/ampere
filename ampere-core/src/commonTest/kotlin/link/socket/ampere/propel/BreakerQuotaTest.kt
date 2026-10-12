package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.DecodedRows
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.UndecodableRow
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.field.Admission
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Rule
import link.socket.ampere.plug.permission.UserGrants
import link.socket.ampere.time.MutableClock

/**
 * AMPR-445 (F14): the Breaker's quota rules, its transition rule, and the rule it refuses.
 *
 * Each quota is read from a run's record — here a scripted list of [StoredEvent]s standing
 * in for what the door would return — and trips once the spend *reaches* its limit, so each
 * is asserted under, at and over. The hosted run that feeds these rules from a real door is
 * the jvmTest `HostedRunQuotaTest`'s.
 */
class BreakerQuotaTest {

    // ==================== Tokens ====================

    @Test
    fun `a token spend under its limit passes`() = runTest {
        val admission = breaker(record(calls(400))).admit(STEP_TOOL, listOf(tokens(1_000)))

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    @Test
    fun `a token spend at its limit trips naming the quota and the limit and the spend`() = runTest {
        val admission = breaker(record(calls(400, 600))).admit(STEP_TOOL, listOf(tokens(1_000)))

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertTrue(trip.reason.contains("token quota"), trip.reason)
        assertTrue(trip.reason.contains("limit of 1000"), trip.reason)
        assertTrue(trip.reason.contains("spent 1000 tokens"), trip.reason)
    }

    @Test
    fun `a token spend over its limit trips`() = runTest {
        val admission = breaker(record(calls(400, 600, 50))).admit(STEP_TOOL, listOf(tokens(1_000)))

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertTrue(trip.reason.contains("spent 1050 tokens"), trip.reason)
    }

    @Test
    fun `tokens are input plus output and an unreported count adds nothing`() = runTest {
        val events = listOf(
            stored(1, providerCall(TokenUsage(inputTokens = 300, outputTokens = 200))),
            stored(2, providerCall(TokenUsage(inputTokens = null, outputTokens = 100))),
            stored(3, providerCall(TokenUsage())),
        )

        val under = breaker(record(events)).admit(STEP_TOOL, listOf(tokens(601))).getOrThrow()
        val at = breaker(record(events)).admit(STEP_TOOL, listOf(tokens(600))).getOrThrow()

        assertEquals(Admission.Pass, under)
        assertIs<Admission.Trip>(at)
    }

    // ==================== Time ====================

    @Test
    fun `elapsed time under its limit passes`() = runTest {
        val admission = breaker(record(calls(10)), clock = clockAt(START + 9.minutes))
            .admit(STEP_TOOL, listOf(time(10.minutes)))

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    @Test
    fun `elapsed time at its limit trips naming the quota and the limit and the spend`() = runTest {
        val admission = breaker(record(calls(10)), clock = clockAt(START + 10.minutes))
            .admit(STEP_TOOL, listOf(time(10.minutes)))

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertTrue(trip.reason.contains("wall-clock quota"), trip.reason)
        assertTrue(trip.reason.contains("run for 10m"), trip.reason)
        assertTrue(trip.reason.contains("limit of 10m"), trip.reason)
    }

    @Test
    fun `elapsed time over its limit trips`() = runTest {
        val admission = breaker(record(calls(10)), clock = clockAt(START + 25.minutes))
            .admit(STEP_TOOL, listOf(time(10.minutes)))

        assertIs<Admission.Trip>(admission.getOrThrow())
    }

    @Test
    fun `elapsed time is measured from the first recorded event and not the latest`() = runTest {
        val events = listOf(
            stored(1, providerCall(TokenUsage()), recordedAt = START),
            stored(2, providerCall(TokenUsage()), recordedAt = START + 8.minutes),
        )

        val admission = breaker(record(events), clock = clockAt(START + 10.minutes))
            .admit(STEP_TOOL, listOf(time(10.minutes)))

        assertIs<Admission.Trip>(admission.getOrThrow())
    }

    @Test
    fun `a run with nothing on the record has spent no time`() = runTest {
        val admission = breaker(record(emptyList()), clock = clockAt(START + 1_000.minutes))
            .admit(STEP_TOOL, listOf(time(1.minutes)))

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    // ==================== Count ====================

    @Test
    fun `an executed step count under its limit passes`() = runTest {
        val admission = breaker(record(completedSteps(2))).admit(STEP_TOOL, listOf(stepQuota(limit = 3)))

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    @Test
    fun `an executed step count at its limit trips naming the quota and the limit and the spend`() = runTest {
        val admission = breaker(record(completedSteps(3))).admit(STEP_TOOL, listOf(stepQuota(limit = 3)))

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertTrue(trip.reason.contains("step quota"), trip.reason)
        assertTrue(trip.reason.contains("executed 3 steps"), trip.reason)
        assertTrue(trip.reason.contains("limit of 3"), trip.reason)
    }

    @Test
    fun `an executed step count over its limit trips`() = runTest {
        val admission = breaker(record(completedSteps(5))).admit(STEP_TOOL, listOf(stepQuota(limit = 3)))

        assertIs<Admission.Trip>(admission.getOrThrow())
    }

    @Test
    fun `only completed steps are counted`() = runTest {
        val events = calls(100, 100, 100) + completedSteps(1, firstSequence = 10)

        val admission = breaker(record(events)).admit(STEP_TOOL, listOf(stepQuota(limit = 2)))

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    @Test
    fun `a count of anything but steps is a failure`() = runTest {
        val rule = Rule.Quota.Count(limit = 3, of = "files") to Hardness.HARD

        val admission = breaker(record(completedSteps(1))).admit(STEP_TOOL, listOf(rule))

        val failure = assertIs<IllegalStateException>(admission.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("files"), "got '${failure.message}'")
    }

    // ==================== The record ====================

    @Test
    fun `a quota for a Breaker with no run is a failure`() = runTest {
        val breaker = Breaker(
            userGrants = { UserGrants() },
            executorId = EXECUTOR_ID,
            runId = null,
            runEvents = record(calls(10)),
        )

        val admission = breaker.admit(STEP_TOOL, listOf(tokens(1_000)))

        assertTrue(admission.isFailure, "a quota for no run is never a pass")
    }

    @Test
    fun `a record that could not be read is a failure and never a pass`() = runTest {
        val breaker = breaker(runEvents = { Result.failure(IllegalStateException("the store is closed")) })

        val admission = breaker.admit(STEP_TOOL, listOf(tokens(1_000)))

        val failure = assertIs<IllegalStateException>(admission.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("the store is closed"), "got '${failure.message}'")
    }

    @Test
    fun `a record with an undecodable row is a failure because its spend is unknown`() = runTest {
        val rows = DecodedRows(
            calls(10),
            undecodable = listOf(UndecodableRow(rowId = "row-from-a-newer-build", reason = "unknown event type")),
        )
        val breaker = breaker(runEvents = { Result.success(rows) })

        val admission = breaker.admit(STEP_TOOL, listOf(tokens(1_000)))

        val failure = assertIs<IllegalStateException>(admission.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("cannot decode"), "got '${failure.message}'")
    }

    @Test
    fun `the record is read once however many quotas an admission holds`() = runTest {
        var reads = 0
        val breaker = breaker(
            runEvents = { _ ->
                reads++
                Result.success(DecodedRows(calls(10) + completedSteps(1, firstSequence = 5)))
            },
        )

        val admission = breaker.admit(STEP_TOOL, listOf(tokens(1_000), time(10.minutes), stepQuota(limit = 3)))

        assertEquals(Admission.Pass, admission.getOrThrow())
        assertEquals(1, reads)
    }

    @Test
    fun `an admission with no quota reads nothing`() = runTest {
        var reads = 0
        val breaker = breaker(
            runEvents = { _ ->
                reads++
                Result.success(DecodedRows(emptyList()))
            },
        )

        val admission = breaker.admit(STEP_TOOL, emptyList())

        assertEquals(Admission.Pass, admission.getOrThrow())
        assertEquals(0, reads)
    }

    @Test
    fun `a step that nominates no tool is admitted against the quotas`() = runTest {
        val admission = breaker(record(calls(1_000))).admit(tool = null, rules = listOf(tokens(1_000)))

        assertIs<Admission.Trip>(admission.getOrThrow())
    }

    @Test
    fun `an access policy for a step that nominates no tool is a failure`() = runTest {
        val rule = Rule.AccessPolicy(setOf("anything")) to Hardness.HARD

        val admission = breaker(record(emptyList())).admit(tool = null, rules = listOf(rule))

        assertTrue(admission.isFailure, "a rule with nothing to check against is never a pass")
    }

    // ==================== RunQuotas ====================

    @Test
    fun `run quotas become one Hard rule each and none for none`() {
        val quotas = RunQuotas(tokens = 5_000, wallClock = 30.minutes, steps = 12)

        assertEquals(
            listOf(
                Rule.Quota.Tokens(5_000) to Hardness.HARD,
                Rule.Quota.Time(30.minutes) to Hardness.HARD,
                Rule.Quota.Count(limit = 12, of = RunQuotas.STEPS) to Hardness.HARD,
            ),
            quotas.asRules(),
        )
        assertTrue(RunQuotas.None.asRules().isEmpty())
    }

    // ==================== Requirements and Transition ====================

    @Test
    fun `requirements handed to the Breaker are a failure routed to verification`() = runTest {
        val rule = Rule.Requirements() to Hardness.HARD

        val admission = breaker(record(emptyList())).admit(STEP_TOOL, listOf(rule))

        val failure = assertIs<IllegalStateException>(admission.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("Requirements"), "got '${failure.message}'")
        assertTrue(failure.message.orEmpty().contains("verification"), "got '${failure.message}'")
    }

    @Test
    fun `a transition handed to admit is a failure because a step proposes no status change`() = runTest {
        val rule = TicketStatus.asTransitionRule() to Hardness.HARD

        val admission = breaker(record(emptyList())).admit(STEP_TOOL, listOf(rule))

        assertTrue(admission.isFailure, "a transition with no proposed change is never a pass")
    }

    @Test
    fun `an allowed transition passes`() {
        val rule = Rule.Transition(mapOf("Ready" to setOf("In Progress")))

        assertEquals(Admission.Pass, Breaker.admitTransition(rule, from = "Ready", to = "In Progress"))
    }

    @Test
    fun `a transition the rule does not list trips naming the move`() {
        val rule = Rule.Transition(mapOf("Ready" to setOf("In Progress")))

        val trip = assertIs<Admission.Trip>(Breaker.admitTransition(rule, from = "Ready", to = "Done"))
        assertTrue(trip.reason.contains("'Ready' may not move to 'Done'"), trip.reason)
        assertTrue(trip.reason.contains("In Progress"), trip.reason)
    }

    @Test
    fun `the ticket transition rule is validTransitions for every status`() {
        val rule = TicketStatus.asTransitionRule()

        assertEquals(TicketStatus.values.map { it.name }.toSet(), rule.allowed.keys)
        TicketStatus.values.forEach { status ->
            assertEquals(
                status.validTransitions().map { it.name }.toSet(),
                rule.allowed.getValue(status.name),
                "allowed moves from ${status.name}",
            )
        }
    }

    @Test
    fun `ticket transitions are decided by the Breaker evaluation`() {
        val rule = TicketStatus.asTransitionRule()

        TicketStatus.values.forEach { from ->
            TicketStatus.values.forEach { to ->
                val admitted = Breaker.admitTransition(rule, from = from.name, to = to.name) == Admission.Pass
                assertEquals(to in from.validTransitions(), admitted, "${from.name} to ${to.name}")
                assertEquals(admitted, from.canTransitionTo(to), "${from.name} to ${to.name}")
            }
        }
    }

    @Test
    fun `a state the rule does not list may move nowhere`() {
        val rule = Rule.Transition(mapOf("Ready" to setOf("In Progress")))

        assertIs<Admission.Trip>(Breaker.admitTransition(rule, from = "Archived", to = "Ready"))
    }

    // ==================== Fixtures ====================

    private fun breaker(
        runEvents: suspend (String) -> Result<DecodedRows<StoredEvent>>,
        clock: MutableClock = clockAt(START),
    ): Breaker = Breaker(
        userGrants = { UserGrants() },
        executorId = EXECUTOR_ID,
        runId = RUN_ID,
        runEvents = runEvents,
        clock = clock,
    )

    /** A record that returns [events] for [RUN_ID] and refuses any other run. */
    private fun record(events: List<StoredEvent>): suspend (String) -> Result<DecodedRows<StoredEvent>> = { runId ->
        assertEquals(RUN_ID, runId, "the Breaker reads the run it was built for")
        Result.success(DecodedRows(events))
    }

    private fun clockAt(now: Instant): MutableClock = MutableClock(now)

    /** One completed provider call per entry, each spending that many tokens (split input/output), from [START]. */
    private fun calls(vararg tokens: Int): List<StoredEvent> = tokens.mapIndexed { index, total ->
        stored(
            sequence = index + 1L,
            event = providerCall(TokenUsage(inputTokens = total / 2, outputTokens = total - total / 2)),
        )
    }

    /** [count] completed plan steps, sequenced from [firstSequence]. */
    private fun completedSteps(count: Int, firstSequence: Long = 1): List<StoredEvent> = (0 until count).map { index ->
        stored(
            sequence = firstSequence + index,
            event = PlanEvent.PlanStepCompleted(
                eventId = "step-completed-$index",
                planId = "plan",
                stepId = "step-$index",
                stepDescription = "step $index",
                stepIndex = index,
                totalSteps = count,
                outcome = StepOutcome.Success(
                    id = "step-$index",
                    stepDescription = "step $index",
                    startTimestamp = START,
                    endTimestamp = START,
                    details = "done",
                ),
                eventSource = EventSource.Agent(EXECUTOR_ID),
                timestamp = START,
                runId = RUN_ID,
            ),
        )
    }

    private fun providerCall(usage: TokenUsage) = ProviderCallCompletedEvent(
        eventId = "call-${usage.hashCode()}",
        timestamp = START,
        eventSource = EventSource.Agent(EXECUTOR_ID),
        workflowId = RUN_ID,
        agentId = EXECUTOR_ID,
        providerId = "anthropic",
        modelId = "test-model",
        usage = usage,
        latencyMs = 1,
        success = true,
    )

    private fun stored(
        sequence: Long,
        event: Event,
        recordedAt: Instant = START,
    ) = StoredEvent(
        event = event,
        sequence = sequence,
        recordedAt = recordedAt,
        causedBy = null,
        runId = RUN_ID,
    )

    private fun tokens(limit: Long) = Rule.Quota.Tokens(limit) to Hardness.HARD

    private fun time(limit: Duration) = Rule.Quota.Time(limit) to Hardness.HARD

    private fun stepQuota(limit: Int) = Rule.Quota.Count(limit = limit, of = RunQuotas.STEPS) to Hardness.HARD

    private companion object {
        const val EXECUTOR_ID = "breaker-quota-test-agent"
        const val RUN_ID = "run-under-quota"

        val START: Instant = Instant.parse("2026-10-11T12:00:00Z")

        val STEP_TOOL: Tool<*> = FunctionTool<ExecutionContext>(
            id = "search",
            name = "search",
            description = "test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { error("the Breaker never runs a tool") },
        )
    }
}
