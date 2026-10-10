package link.socket.ampere.llm.decide

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.trace.ArcTraceProjection

/**
 * J4: `AgentReasoning.decide` leaves one `JudgmentRecorded` per judgment through the agent's door,
 * under the run id, carrying a digest of the state and never the state — and the trace files it
 * under the phase that asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JudgmentRecordedThroughDoorTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private lateinit var door: InMemoryEventDoor.Handle

    private val aiConfiguration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)
    private val state = "SECRET-STATE-TEXT: Alice Example asks for a refund of the duplicate charge."
    private val refund = Question.Noul.of("Is money being asked back?", whenTrue = "Refund", whenFalse = "Other")
    private val team = Question.Choice(
        instructions = "Which team?",
        criteria = linkedMapOf("billing" to "Money", "technical" to "Bugs"),
    )

    @BeforeTest
    fun setUp() {
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        door.close()
    }

    private fun reasoningWith(client: UpstreamDecisionClient, runId: String): AgentReasoning =
        AgentReasoning.create(
            config = AgentConfiguration(
                agentDefinition = AgentDefinition.Custom(
                    name = "decide-door-test",
                    description = "AMPR-384 record test agent",
                    prompt = "You are a test agent.",
                ),
                aiConfiguration = aiConfiguration,
                upstreamDecisionClient = client,
            ),
            executorId = AGENT_ID,
            eventApi = door.api,
            runId = runId,
        ) {
            agentRole = "Test Agent"
        }

    @Test
    fun `decide records one judgment per question under the run with a digest and never the state`() {
        val runId = "run-decide-1"
        val rule = DeterministicDecisionClient(name = "refund-rule") { s, q ->
            when (q) {
                is Question.Noul -> if (s.contains("refund")) Question.Noul.TRUE else Question.Noul.FALSE
                else -> q.answerKeys.first()
            }
        }
        val reasoning = reasoningWith(rule, runId)

        runBlocking {
            reasoning.decide(
                state = state,
                questions = mapOf("refund" to refund, "team" to team),
                phase = CognitivePhase.PLAN,
                causedBy = "action-42",
            )
        }

        val trace = runBlocking {
            ArcTraceProjection(door.database).project(runId = runId, arcId = "test-arc")
        }.getOrThrow()
        val plan = assertNotNull(
            trace.phases.singleOrNull { it.name == "PLAN" },
            "records are filed under the asking phase",
        )
        val recorded = plan.events.filter { it.eventType == CognitiveEvent.JudgmentRecorded.EVENT_TYPE }
        assertEquals(2, recorded.size, "one record per judgment")

        val decoded = recorded.map { traceEvent ->
            val payload = assertNotNull(traceEvent.payload)
            assertFalse(payload.contains("SECRET-STATE-TEXT"), "the state never appears in the record")
            assertFalse(payload.contains("Alice"), "the state never appears in the record")
            assertTrue(payload.contains(stateDigest(state)), "the record carries the state's digest")
            assertIs<CognitiveEvent.JudgmentRecorded>(DEFAULT_JSON.decodeFromString(Event.serializer(), payload))
        }

        assertEquals(setOf("refund", "team"), decoded.map { it.questionId }.toSet())
        assertEquals(1, decoded.map { it.callId }.distinct().size, "both records belong to one call")
        decoded.forEach { record ->
            assertEquals(AGENT_ID, record.agentId)
            assertEquals(stateDigest(state), record.stateDigest)
            assertEquals(ConfidenceSource.MEASURED, record.source)
            assertNotNull(record.distribution)
            assertEquals(1.0, record.confidence)
            assertEquals(null, record.band)
            assertEquals("action-42", record.causedBy)
            assertEquals(CognitivePhase.PLAN, record.cognitivePhase)
            assertEquals(DeterministicDecisionClient.PROVIDER_ID, record.modelSnapshot.providerId)
            assertTrue(record.latencyMs >= 0)
        }
        val refundRecord = decoded.single { it.questionId == "refund" }
        assertEquals("noul", refundRecord.questionType)
        assertEquals(refund.version, refundRecord.questionVersion)
        assertEquals("true", refundRecord.answer)
        assertEquals("choice", decoded.single { it.questionId == "team" }.questionType)

        // The row's own caused_by carries the same link, so the judgment is reachable from the action.
        val causedRows = runBlocking { door.repository.getEventsCausedBy("action-42") }.getOrThrow()
        assertEquals(2, causedRows.size)
    }

    @Test
    fun `a transport that throws leaves no record`() {
        val runId = "run-decide-2"
        val broken = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse =
                throw IllegalStateException("endpoint down")
        }
        val reasoning = reasoningWith(broken, runId)

        assertFailsWith<IllegalStateException> {
            runBlocking { reasoning.decide(state = state, questions = mapOf("refund" to refund)) }
        }

        val stored = runBlocking {
            door.repository.getEventsByType(CognitiveEvent.JudgmentRecorded.EVENT_TYPE)
        }.getOrThrow()
        assertTrue(stored.isEmpty())
    }

    private companion object {
        const val AGENT_ID = "decider"
    }
}
