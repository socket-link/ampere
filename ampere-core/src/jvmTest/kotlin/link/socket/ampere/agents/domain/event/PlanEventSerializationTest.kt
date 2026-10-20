package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.outcome.TrippedRule
import link.socket.ampere.data.DEFAULT_JSON

/**
 * AMPR-472 (F33): `PlanEvent.StepAdmitted` and a tripped step's `StepOutcome.Failure` through
 * the serializer the event store writes with.
 *
 * The wire names are asserted as well as the round trip, because they are what a reader of
 * the stored payload matches on: the event and each admission carry a `@SerialName` rather
 * than a class path, and a failure no rule caused has no `tripped` key at all, so every
 * failure stored before the field existed reads the same as one stored after it.
 */
class PlanEventSerializationTest {

    @Test
    fun `each admission round-trips through the store's serializer`() {
        listOf(PASS, RESIST, TRIP).forEach { admission ->
            val original: Event = stepAdmitted(admission)

            val text = DEFAULT_JSON.encodeToString(Event.serializer(), original)

            assertEquals(original, DEFAULT_JSON.decodeFromString(Event.serializer(), text))
        }
    }

    @Test
    fun `the event and its admission are named on the wire by their serial names`() {
        val text = DEFAULT_JSON.encodeToString(Event.serializer(), stepAdmitted(TRIP))

        val payload = Json.parseToJsonElement(text).jsonObject
        assertEquals("PlanEvent.StepAdmitted", payload.getValue("type").jsonPrimitive.content)
        val admission = payload.getValue("admission").jsonObject
        assertEquals("AdmissionRecord.Trip", admission.getValue("type").jsonPrimitive.content)
        assertEquals(TRIP.rule, admission.getValue("rule").jsonPrimitive.content)
    }

    @Test
    fun `a tripped failure round-trips on PlanStepCompleted`() {
        val original: Event = PlanEvent.PlanStepCompleted(
            eventId = "plan-step-completed-1",
            planId = PLAN_ID,
            stepId = STEP_ID,
            stepDescription = STEP_DESCRIPTION,
            stepIndex = 2,
            totalSteps = 3,
            outcome = failure(tripped = TrippedRule(rule = TRIP.rule, reason = TRIP.reason)),
            eventSource = EventSource.Agent(AGENT_ID),
            timestamp = AT,
            runId = RUN_ID,
        )

        val text = DEFAULT_JSON.encodeToString(Event.serializer(), original)

        assertEquals(original, DEFAULT_JSON.decodeFromString(Event.serializer(), text))
    }

    @Test
    fun `a failure no rule caused encodes exactly as it did before the field`() {
        val text = DEFAULT_JSON.encodeToString(StepOutcome.serializer(), failure(tripped = null))

        assertFalse("tripped" in Json.parseToJsonElement(text).jsonObject, "got $text")
        assertEquals(failure(tripped = null), DEFAULT_JSON.decodeFromString(StepOutcome.serializer(), text))
    }

    @Test
    fun `the event type is registered and parses by name`() {
        assertEquals(true, PlanEvent.StepAdmitted.EVENT_TYPE in EventRegistry.allEventTypes)
        assertEquals(PlanEvent.StepAdmitted.EVENT_TYPE, EventRegistry.parseEventType("planstepadmitted"))
    }

    private fun stepAdmitted(admission: AdmissionRecord) = PlanEvent.StepAdmitted(
        eventId = "plan-step-admitted-1",
        planId = PLAN_ID,
        stepId = STEP_ID,
        stepDescription = STEP_DESCRIPTION,
        runId = RUN_ID,
        admission = admission,
        eventSource = EventSource.Agent(AGENT_ID),
        timestamp = AT,
    )

    private fun failure(tripped: TrippedRule?) = StepOutcome.Failure(
        id = STEP_ID,
        stepDescription = STEP_DESCRIPTION,
        startTimestamp = AT,
        endTimestamp = AT,
        error = TRIP.reason,
        tripped = tripped,
    )

    private companion object {
        const val AGENT_ID = "plan-event-serialization-agent"
        const val PLAN_ID = "plan-1"
        const val STEP_ID = "step-3"
        const val RUN_ID = "run-plan-event-serialization"
        const val STEP_DESCRIPTION = "Write the summary"

        val AT: Instant = Instant.parse("2026-10-20T12:00:00Z")

        val PASS = AdmissionRecord.Pass
        val RESIST = AdmissionRecord.Resist(degree = 2.0, rules = listOf("Quota.Tokens", "AccessPolicy:example"))
        val TRIP = AdmissionRecord.Trip(
            rule = "Quota.Count:steps",
            reason = "step quota reached: run 'run-plan-event-serialization' has executed 2 steps against a limit of 2",
        )
    }
}
