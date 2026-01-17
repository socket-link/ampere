package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.KnowledgeExtractor
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory

/**
 * A concrete agent implementation using the Spark-based cognitive differentiation system.
 *
 * SparkBasedAgent is a general-purpose agent that derives its behavior from
 * its CognitiveAffinity and accumulated Sparks rather than from hardcoded
 * class implementations. This enables flexible, observable cognitive contexts
 * without requiring separate agent classes for each specialization.
 *
 * The system prompt is dynamically built from the SparkStack before each LLM
 * interaction, and tool/file access is computed from Spark constraints.
 *
 * Note: This uses CodeState as a simple state implementation. Custom state types
 * can be supported by subclassing.
 *
 * @param agentId Unique identifier for this agent
 * @param cognitiveAffinity The cognitive affinity that shapes how this agent thinks
 * @param eventApi Optional event API for observability
 * @param agentMemoryService Optional memory service for knowledge persistence
 * @param aiConfiguration Optional AI configuration (uses default if not provided)
 */
@Serializable
class SparkBasedAgent(
    private val agentId: AgentId,
    private val cognitiveAffinity: CognitiveAffinity,
    @Transient
    private val _eventApi: AgentEventApi? = null,
    @Transient
    private val _memoryService: AgentMemoryService? = null,
    @Transient
    private val _aiConfiguration: AIConfiguration? = null,
    @Transient
    private val _observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : ObservableAgent<CodeState>(_eventApi, _observabilityScope) {

    override val id: AgentId = agentId

    override val affinity: CognitiveAffinity = cognitiveAffinity

    override val initialState: CodeState = CodeState.blank

    @Transient
    override val memoryService: AgentMemoryService? = _memoryService

    private val effectiveAiConfiguration: AIConfiguration
        get() = _aiConfiguration ?: AIConfigurationFactory.getDefaultConfiguration()

    override val agentConfiguration: AgentConfiguration
        get() = AgentConfiguration(
            agentDefinition = AgentDefinition.Custom(
                name = "SparkBasedAgent-$id",
                description = "A Spark-based agent with affinity: ${affinity.name}",
                prompt = currentSystemPrompt,
            ),
            aiConfiguration = effectiveAiConfiguration,
        )

    // Initialize the SparkStack with the configured affinity
    init {
        reinitializeSparkStack()
    }

    // ========================================================================
    // Reasoning Infrastructure
    // ========================================================================

    private val reasoning: AgentReasoning by lazy {
        AgentReasoning.create(agentConfiguration, id) {
            agentRole = "Spark-Based Agent (${affinity.name})"
            availableTools = requiredTools

            knowledge {
                extractor = { outcome, task, plan ->
                    KnowledgeExtractor.extract(outcome, task, plan) {
                        approach {
                            prefix("Spark Task [${this@SparkBasedAgent.cognitiveState}]")
                            taskType(task)
                            planSize(plan)
                        }
                        learnings {
                            fromOutcome(outcome)
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // Neural Agent Implementation
    // ========================================================================

    @Suppress("UNCHECKED_CAST")
    override val runLLMToEvaluatePerception: (Perception<CodeState>) -> Idea = { perception ->
        runBlocking(Dispatchers.IO) {
            withTimeout(60000) {
                reasoning.evaluatePerception(perception)
            }
        }
    }

    override val runLLMToPlan: (Task, List<Idea>) -> Plan = { task, ideas ->
        runBlocking(Dispatchers.IO) {
            withTimeout(60000) {
                reasoning.generatePlan(task, ideas)
            }
        }
    }

    override val runLLMToExecuteTask: (Task) -> Outcome = { task ->
        runBlocking(Dispatchers.IO) {
            withTimeout(60000) {
                val plan = reasoning.generatePlan(task, emptyList())
                reasoning.executePlan(plan) { step, _ ->
                    StepResult.success(
                        description = "Executed: ${step.id}",
                        details = "Completed step",
                    )
                }.outcome
            }
        }
    }

    override val runLLMToExecuteTool: (Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome = { tool, request ->
        runBlocking(Dispatchers.IO) {
            withTimeout(60000) {
                reasoning.executeTool(tool, request)
            }
        }
    }

    override val runLLMToEvaluateOutcomes: (List<Outcome>) -> Idea = { outcomes ->
        runBlocking(Dispatchers.IO) {
            withTimeout(60000) {
                reasoning.evaluateOutcomes(outcomes, memoryService).summaryIdea
            }
        }
    }

    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge = reasoning.extractKnowledge(outcome, task, plan)

    override fun callLLM(prompt: String): String = runBlocking(Dispatchers.IO) {
        withTimeout(60000) {
            reasoning.callLLM(prompt)
        }
    }
}
