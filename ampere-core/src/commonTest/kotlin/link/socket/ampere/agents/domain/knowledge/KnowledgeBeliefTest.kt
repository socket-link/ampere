package link.socket.ampere.agents.domain.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import link.socket.ampere.field.Belief
import link.socket.ampere.field.Decay
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.subject.SubjectRegistry

/**
 * [toBelief] (AMPR-439): a stored knowledge row read as the Field's [Belief]. A confidence makes
 * it settled and its absence unsettled; holder and sensitivity are the row's; provenance is the
 * row's event; a row naming no event is a failure, never a belief with an invented provenance.
 */
class KnowledgeBeliefTest {

    private val recordedAt = Instant.fromEpochMilliseconds(1_760_000_000_000)
    private val about: Ref<Primitive> = Ref<Goal>("run-1/goal-0")

    private fun entry(
        confidence: Double? = null,
        createdByEvent: String? = "knowledge-stored-1",
        approach: String = "Retry with backoff",
        learnings: String = "Transient failures clear on their own",
    ) = KnowledgeEntry(
        id = "knowledge-1",
        knowledgeType = KnowledgeType.FROM_OUTCOME,
        approach = approach,
        learnings = learnings,
        timestamp = recordedAt,
        outcomeId = "outcome-1",
        holder = Ref("agent-a"),
        confidence = confidence,
        sensitivity = Sensitivity.PRIVATE,
        createdByEvent = createdByEvent,
        runId = "run-1",
    )

    @Test
    fun `a row with a confidence is a settled belief`() {
        val belief = assertIs<Belief.Settled>(entry(confidence = 0.75).toBelief(about).getOrThrow())

        assertEquals(0.75, belief.confidence)
        assertEquals(Ref("agent-a"), belief.holder)
        assertEquals(about, belief.subject)
        assertEquals(Sensitivity.PRIVATE, belief.sensitivity)
        assertEquals(Decay.None, belief.decay)
        assertEquals(Provenance(createdBy = "knowledge-stored-1", createdAt = recordedAt), belief.provenance)
    }

    @Test
    fun `a row without a confidence is an unsettled belief`() {
        val belief = assertIs<Belief.Unsettled>(entry(confidence = null).toBelief(about).getOrThrow())

        assertNull(belief.confidence)
        assertEquals(Ref("agent-a"), belief.holder)
        assertEquals(Provenance(createdBy = "knowledge-stored-1", createdAt = recordedAt), belief.provenance)
    }

    @Test
    fun `the content is the approach and then what was learned from it`() {
        val belief = entry().toBelief(about).getOrThrow()

        assertEquals("Retry with backoff\n\nTransient failures clear on their own", belief.content)
    }

    @Test
    fun `a blank approach leaves only what was learned`() {
        val belief = entry(approach = " ").toBelief(about).getOrThrow()

        assertEquals("Transient failures clear on their own", belief.content)
    }

    @Test
    fun `a backfilled row keeps the system holder it was given`() {
        val backfilled = entry().copy(holder = SubjectRegistry.SYSTEM_REF)

        assertEquals(SubjectRegistry.SYSTEM_REF, backfilled.toBelief(about).getOrThrow().holder)
    }

    @Test
    fun `a row that names no event is a failure and not a belief`() {
        val failure = entry(createdByEvent = null).toBelief(about).exceptionOrNull()

        val unprovenanced = assertIs<UnprovenancedKnowledgeException>(failure)
        assertEquals("knowledge-1", unprovenanced.knowledgeId)
    }
}
