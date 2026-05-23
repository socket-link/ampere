package link.socket.ampere.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId

class MemoryStoreTest {

    @Test
    fun `memoryStoreOf wires the supplied repositories`() {
        val knowledge = RecordingKnowledgeRepository()
        val outcomes = RecordingOutcomeRepository()

        val store = memoryStoreOf(knowledge = knowledge, outcomes = outcomes)

        assertSame(knowledge, store.knowledge, "knowledge property must return the injected repository")
        assertSame(outcomes, store.outcomes, "outcomes property must return the injected repository")
    }

    @Test
    fun `delegates writes through composed repositories`() = runTest {
        val knowledge = RecordingKnowledgeRepository()
        val outcomes = RecordingOutcomeRepository()
        val store = memoryStoreOf(knowledge = knowledge, outcomes = outcomes)

        val now = Clock.System.now()
        store.knowledge.storeKnowledge(
            knowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-1",
                approach = "TDD",
                learnings = "tests first surfaces bugs early",
                timestamp = now,
            ),
            tags = listOf("testing"),
            taskType = "feature",
        ).getOrThrow()

        store.outcomes.recordOutcome(
            ticketId = "ticket-1",
            executorId = "executor-1",
            approach = "TDD",
            outcome = ExecutionOutcome.NoChanges.Success(
                executorId = "executor-1",
                ticketId = "ticket-1",
                taskId = "task-1",
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = "ok",
            ),
            timestamp = now,
        ).getOrThrow()

        assertEquals(1, knowledge.stored.size)
        assertEquals(1, outcomes.recorded.size)
        assertEquals("ticket-1", outcomes.recorded[0].ticketId)
    }

    @Test
    fun `custom MemoryStore implementations satisfy the contract`() {
        val knowledge = RecordingKnowledgeRepository()
        val outcomes = RecordingOutcomeRepository()

        val store = object : MemoryStore {
            override val knowledge: KnowledgeRepository = knowledge
            override val outcomes: OutcomeMemoryRepository = outcomes
        }

        // Verifies the public interface shape is implementable without
        // depending on the bundled factory — Socket's client-side impl
        // takes this code path.
        assertTrue(store.knowledge === knowledge)
        assertTrue(store.outcomes === outcomes)
    }
}

private class RecordingKnowledgeRepository : KnowledgeRepository {

    val stored = mutableListOf<Knowledge>()

    override suspend fun storeKnowledge(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
    ): Result<KnowledgeEntry> {
        stored += knowledge
        return Result.success(
            KnowledgeEntry(
                id = "entry-${stored.size}",
                knowledgeType = KnowledgeType.FROM_OUTCOME,
                approach = "stub",
                learnings = "stub",
                timestamp = Clock.System.now(),
                tags = tags,
                taskType = taskType,
                complexityLevel = complexityLevel,
            ),
        )
    }

    override suspend fun findSimilarKnowledge(
        description: String,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = Result.success(emptyList())

    override suspend fun findKnowledgeByType(
        knowledgeType: KnowledgeType,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = Result.success(emptyList())

    override suspend fun findKnowledgeByTaskType(
        taskType: String,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = Result.success(emptyList())

    override suspend fun findKnowledgeByTag(
        tag: String,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = Result.success(emptyList())

    override suspend fun findKnowledgeByTags(
        tags: List<String>,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = Result.success(emptyList())

    override suspend fun findKnowledgeByTimeRange(
        fromTimestamp: kotlinx.datetime.Instant,
        toTimestamp: kotlinx.datetime.Instant,
    ): Result<List<KnowledgeEntry>> = Result.success(emptyList())

    override suspend fun searchKnowledgeByContext(
        knowledgeType: KnowledgeType?,
        taskType: String?,
        tags: List<String>?,
        complexityLevel: String?,
        fromTimestamp: kotlinx.datetime.Instant?,
        toTimestamp: kotlinx.datetime.Instant?,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = Result.success(emptyList())

    override suspend fun getKnowledgeById(id: String): Result<KnowledgeEntry?> =
        Result.success(null)

    override suspend fun getTagsForKnowledge(knowledgeId: String): Result<List<String>> =
        Result.success(emptyList())
}

private class RecordingOutcomeRepository : OutcomeMemoryRepository {

    val recorded = mutableListOf<OutcomeMemory>()

    override suspend fun recordOutcome(
        ticketId: TicketId,
        executorId: ExecutorId,
        approach: String,
        outcome: ExecutionOutcome,
        timestamp: kotlinx.datetime.Instant,
    ): Result<OutcomeMemory> {
        val memory = OutcomeMemory(
            id = "memory-${recorded.size}",
            ticketId = ticketId,
            executorId = executorId,
            approach = approach,
            success = outcome is ExecutionOutcome.NoChanges.Success,
            executionDurationMs = 0L,
            filesChanged = 0,
            errorMessage = null,
            timestamp = timestamp,
        )
        recorded += memory
        return Result.success(memory)
    }

    override suspend fun findSimilarOutcomes(
        description: String,
        limit: Int,
    ): Result<List<OutcomeMemory>> = Result.success(emptyList())

    override suspend fun getOutcomesByTicket(
        ticketId: TicketId,
    ): Result<List<OutcomeMemory>> = Result.success(recorded.filter { it.ticketId == ticketId })

    override suspend fun getOutcomesByExecutor(
        executorId: ExecutorId,
        limit: Int,
    ): Result<List<OutcomeMemory>> = Result.success(recorded.filter { it.executorId == executorId })
}
