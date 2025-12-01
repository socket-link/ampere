package link.socket.ampere.agents.core.types

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.memory.KnowledgeEntry
import link.socket.ampere.agents.core.memory.KnowledgeType
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.outcomes.OutcomeId
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import kotlin.time.Duration.Companion.seconds

/**
 * Test helpers for agent type tests
 */

/** Create a test success outcome */
fun testSuccessOutcome(id: OutcomeId): Outcome.Success =
    ExecutionOutcome.NoChanges.Success(
        executorId = "test-executor",
        ticketId = "test-ticket",
        taskId = "test-task",
        executionStartTimestamp = Clock.System.now(),
        executionEndTimestamp = Clock.System.now() + 1.seconds,
        message = "Test success"
    )

/** Create a test failure outcome */
fun testFailureOutcome(id: OutcomeId): Outcome.Failure =
    ExecutionOutcome.NoChanges.Failure(
        executorId = "test-executor",
        ticketId = "test-ticket",
        taskId = "test-task",
        executionStartTimestamp = Clock.System.now(),
        executionEndTimestamp = Clock.System.now() + 1.seconds,
        message = "Test failure"
    )

/** Create a test agent configuration */
fun testAgentConfiguration() = AgentConfiguration(
    agentDefinition = WriteCodeAgent,
    aiConfiguration = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4
    )
)

/** Create a test knowledge entry for FROM_OUTCOME type */
fun testKnowledgeEntry(
    id: String,
    approach: String,
    learnings: String,
    outcomeId: String,
    tags: List<String> = emptyList(),
    taskType: String? = null
) = KnowledgeEntry(
    id = id,
    knowledgeType = KnowledgeType.FROM_OUTCOME,
    approach = approach,
    learnings = learnings,
    timestamp = Clock.System.now(),
    outcomeId = outcomeId,
    taskType = taskType,
    tags = tags
)
