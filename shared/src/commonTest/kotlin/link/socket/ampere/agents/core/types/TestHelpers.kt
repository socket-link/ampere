package link.socket.ampere.agents.core.types

import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.memory.KnowledgeEntry
import link.socket.ampere.agents.core.memory.KnowledgeType
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.outcomes.OutcomeId
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.model.AIModel_Claude

/**
 * Test helpers for agent type tests
 */

/** Test outcome implementations for testing */
data class TestSuccessOutcome(override val id: OutcomeId) : Outcome.Success

data class TestFailureOutcome(override val id: OutcomeId) : Outcome.Failure

/** Create a test agent configuration */
fun testAgentConfiguration() = AgentConfiguration(
    agentDefinition = WriteCodeAgent,
    aiConfiguration = link.socket.ampere.domain.ai.configuration.aiConfiguration(
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
