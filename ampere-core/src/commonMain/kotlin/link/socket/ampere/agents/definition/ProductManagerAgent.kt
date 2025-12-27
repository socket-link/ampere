package link.socket.ampere.agents.definition

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.pm.ProductManagerState
import link.socket.ampere.agents.domain.concept.Idea
import link.socket.ampere.agents.domain.concept.Perception
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.events.tickets.AgentWorkload
import link.socket.ampere.agents.events.tickets.BacklogSummary
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.util.toClientModelId

/**
 * Product Manager Agent responsible for breaking down features into tasks
 * and coordinating implementation efforts.
 *
 * Enhanced with episodic memory—learns which decomposition strategies
 * lead to successful implementations versus which create confusion or rework.
 */
class ProductManagerAgent(
    override val agentConfiguration: AgentConfiguration,
    private val ticketOrchestrator: TicketOrchestrator,
    override val initialState: ProductManagerState = ProductManagerState.blank,
    memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
) : AutonomousAgent<ProductManagerState>() {

    override val id: AgentId = generateUUID("ProductManagerAgent")

    override val memoryService: AgentMemoryService? = memoryServiceFactory?.invoke(id)

    override val runLLMToEvaluatePerception: (perception: Perception<ProductManagerState>) -> Idea =
        { perception -> evaluatePerception(perception) }

    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, ideas -> generatePlanForTask(task, ideas) }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task -> executeTaskWithLLM(task) }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request -> executeToolWithLLM(tool, request) }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes -> evaluateOutcomes(outcomes) }

    /**
     * Overrides getCurrentState to return fresh state from ticket orchestrator.
     *
     * This ensures tests and other callers always see current ticket information.
     */
    override fun getCurrentState(): ProductManagerState = runBlocking {
        getUpdatedAgentState()
    }

    /**
     * Overrides perceiveState to fetch fresh state from ticket orchestrator.
     *
     * This ensures the agent always perceives current ticket information.
     */
    override suspend fun perceiveState(
        currentState: ProductManagerState,
        vararg newIdeas: Idea,
    ): Perception<ProductManagerState> {
        // Get fresh state from ticket orchestrator
        val freshState = getUpdatedAgentState()

        // Create ideas from perception text sections
        val perceptionText = freshState.toPerceptionText()
        val ideas = mutableListOf<Idea>()

        // Parse perception text into ideas by section headers
        ideas.add(Idea(name = "PM Agent Perception State"))
        ideas.add(Idea(name = "Backlog Summary"))
        ideas.add(Idea(name = "Total Tickets: ${freshState.backlogSummary.totalTickets}"))

        if (freshState.blockedTickets.isNotEmpty()) {
            ideas.add(Idea(name = "BLOCKED TICKETS"))
            freshState.blockedTickets.forEach { ticket ->
                ideas.add(Idea(name = ticket.title))
            }
        }

        if (freshState.overdueTickets.isNotEmpty()) {
            ideas.add(Idea(name = "OVERDUE TICKETS"))
        }

        // Create and return perception with these ideas
        return Perception(
            id = generateUUID(id),
            ideas = ideas,
            currentState = freshState,
            timestamp = Clock.System.now(),
        )
    }

    /**
     * UPDATED: Now accepts past knowledge to inform planning.
     *
     * The ProductManager learns patterns like:
     * - Features with comprehensive test plans succeed more often
     * - Breaking features into fewer than 5 tasks reduces overhead
     * - Similar features that worked well (reusable decomposition patterns)
     */
    override suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore>,
    ): Plan {
        // Analyze past knowledge for actionable patterns
        val insights = analyzeKnowledge(relevantKnowledge)

        // Build plan incorporating learned patterns
        val planTasks = mutableListOf<Task>()
        var estimatedComplexity = 5 // Base complexity

        when (task) {
            is Task.CodeChange -> {
                // If past knowledge shows test-first approaches succeeded frequently...
                if (insights.testFirstSuccessRate > 0.7) {
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-test-spec"),
                            status = TaskStatus.Pending,
                            description = "Define test specifications before implementation " +
                                "(Past knowledge shows ${(insights.testFirstSuccessRate * 100).toInt()}% " +
                                "success rate: ${insights.testFirstLearnings.take(100)})",
                            assignedTo = task.assignedTo,
                        ),
                    )
                }

                // If past knowledge identifies common failure points, add preventive steps
                insights.commonFailures.forEach { (failurePoint, learnings) ->
                    planTasks.add(
                        Task.CodeChange(
                            id = generateUUID("${task.id}-validate-${failurePoint.hashCode()}"),
                            status = TaskStatus.Pending,
                            description = "Validate against known failure pattern: $failurePoint " +
                                "(Past learnings: ${learnings.take(100)})",
                            assignedTo = task.assignedTo,
                        ),
                    )
                }

                // Use learned optimal task count for decomposition
                val optimalTasks = insights.optimalTaskCount ?: 5
                planTasks.add(
                    Task.CodeChange(
                        id = generateUUID("${task.id}-implement"),
                        status = TaskStatus.Pending,
                        description = "Implement ${task.description} in approximately $optimalTasks subtasks " +
                            "(Based on past success patterns: ${insights.decompositionLearnings.take(100)})",
                        assignedTo = task.assignedTo,
                    ),
                )

                // Adjust complexity based on insights
                estimatedComplexity = when {
                    insights.commonFailures.isNotEmpty() -> 8 // More failures = higher complexity
                    insights.testFirstSuccessRate > 0.7 -> 4 // Test-first reduces complexity
                    else -> 5
                }
            }
            else -> {
                // Generic task handling
                planTasks.add(task)
            }
        }

        return Plan.ForTask(
            task = task,
            tasks = planTasks,
            estimatedComplexity = estimatedComplexity,
        )
    }

    /**
     * Extract actionable insights from past knowledge entries.
     *
     * This is where the agent "thinks about" what it's learned.
     */
    private fun analyzeKnowledge(knowledge: List<KnowledgeWithScore>): PlanningInsights {
        if (knowledge.isEmpty()) {
            return PlanningInsights() // No history, use defaults
        }

        // Filter to high-relevance knowledge (score > 0.5)
        val relevantKnowledge = knowledge.filter { it.relevanceScore > 0.5 }

        // Analyze learnings for test-first patterns
        val testFirstKnowledge = relevantKnowledge.filter { scored ->
            scored.knowledge.approach.contains("test", ignoreCase = true) ||
                scored.knowledge.learnings.contains("test", ignoreCase = true)
        }
        val testFirstSuccessRate = if (testFirstKnowledge.isNotEmpty()) {
            // Assuming higher relevance scores correlate with successful approaches
            testFirstKnowledge.map { it.relevanceScore }.average()
        } else {
            0.0
        }

        // Extract common failure patterns from learnings
        val failures = relevantKnowledge
            .filter {
                it.knowledge.learnings.contains("failed", ignoreCase = true) ||
                    it.knowledge.learnings.contains("failure", ignoreCase = true)
            }
            .associate { scored ->
                // Extract failure point from learnings (simplified pattern matching)
                val failurePattern = scored.knowledge.learnings
                    .substringAfter("failure", "")
                    .substringAfter("failed", "")
                    .substringBefore(".")
                    .trim()
                    .take(50)
                failurePattern to scored.knowledge.learnings
            }

        // Determine optimal task count from successful decompositions
        val taskCountPattern = Regex("""(\d+)\s*tasks?""")
        val taskCounts = relevantKnowledge.mapNotNull { scored ->
            taskCountPattern.find(scored.knowledge.learnings)?.groupValues?.get(1)?.toIntOrNull()
        }
        val optimalTaskCount = if (taskCounts.isNotEmpty()) {
            taskCounts.average().toInt()
        } else {
            null
        }

        return PlanningInsights(
            testFirstSuccessRate = testFirstSuccessRate,
            testFirstLearnings = testFirstKnowledge.firstOrNull()?.knowledge?.learnings ?: "",
            commonFailures = failures,
            optimalTaskCount = optimalTaskCount,
            decompositionLearnings = relevantKnowledge
                .firstOrNull { it.knowledge.learnings.contains("task", ignoreCase = true) }
                ?.knowledge?.learnings ?: "",
        )
    }

    /**
     * Extract knowledge from completed task for future learning.
     *
     * This is where the agent reflects: "What did I learn that would help next time?"
     */
    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge {
        val taskDescription = when (task) {
            is Task.CodeChange -> task.description
            else -> "Generic task ${task.id}"
        }

        val approachDescription = buildString {
            append("Decomposed '$taskDescription' into ${plan.tasks.size} tasks. ")
            if (plan.tasks.any { t ->
                    when (t) {
                        is Task.CodeChange -> t.description.contains("test", ignoreCase = true)
                        else -> false
                    }
                }
            ) {
                append("Used test-first approach. ")
            }
            append("Complexity: ${plan.estimatedComplexity}")
        }

        val learnings = buildString {
            when (outcome) {
                is Outcome.Success -> {
                    append("Success: ${plan.tasks.size}-task decomposition worked well. ")
                    if (plan.tasks.any { t ->
                            when (t) {
                                is Task.CodeChange -> t.description.contains("test", ignoreCase = true)
                                else -> false
                            }
                        }
                    ) {
                        append("Test-first approach prevented issues. ")
                    }
                    append("Recommend similar decomposition for future tasks.")
                }
                is Outcome.Failure -> {
                    append("Failure occurred during execution. ")
                    append("Recommend adjusting decomposition strategy or adding validation steps. ")
                    append("Consider breaking into ${plan.tasks.size + 2} tasks instead.")
                }
                else -> {
                    append("Partial completion with ${plan.tasks.size} tasks. ")
                    append("May need to refine task granularity.")
                }
            }
        }

        return Knowledge.FromOutcome(
            outcomeId = outcome.id,
            approach = approachDescription,
            learnings = learnings,
            timestamp = Clock.System.now(),
        )
    }

    /**
     * Perceive the current state of the backlog and agent workloads.
     *
     * Retrieves analytics from the ticket system and formats them into
     * a perception state suitable for LLM consumption.
     *
     * @param agentIds Optional list of agent IDs to get workload for. If empty, no workloads are retrieved.
     * @param deadlineDaysAhead Number of days to look ahead for upcoming deadlines (default: 7).
     * @return The PM perception state containing backlog analytics.
     */
    private suspend fun getUpdatedAgentState(
        agentIds: List<AgentId> = emptyList(),
        deadlineDaysAhead: Int = 7,
    ): ProductManagerState {
        // Get backlog summary
        val backlogSummary = ticketOrchestrator.getBacklogSummary()
            .getOrElse { BacklogSummary.Companion.empty() }

        // Get all assigned agents if no specific agents provided
        val allTickets = ticketOrchestrator.getAllTickets().getOrElse { emptyList() }
        val discoveredAgentIds = if (agentIds.isEmpty()) {
            allTickets.mapNotNull { it.assignedAgentId }.distinct()
        } else {
            agentIds
        }

        // Get agent workloads
        val agentWorkloads = discoveredAgentIds.associateWith { agentId ->
            ticketOrchestrator.getAgentWorkload(agentId)
                .getOrElse { AgentWorkload.empty(agentId) }
        }

        // Get upcoming deadlines
        val upcomingDeadlines = ticketOrchestrator.getUpcomingDeadlines(deadlineDaysAhead)
            .getOrElse { emptyList() }

        // Get blocked and overdue tickets from repository
        val allTicketsResult = ticketOrchestrator.getBacklogSummary()

        // Extract blocked tickets
        val blockedTickets = if (allTicketsResult.isSuccess) {
            // We need to get the actual blocked tickets, not just the count
            // For now, collect from agent workloads and deduplicate
            agentWorkloads.values
                .flatMap { it.assignedTickets }
                .filter { it.status == TicketStatus.Blocked }
                .distinctBy { it.id }
        } else {
            emptyList()
        }

        // Extract overdue tickets from upcoming deadlines query
        // We need a separate query for overdue - tickets with due date in the past
        val now = Clock.System.now()
        val overdueTickets = agentWorkloads.values
            .flatMap { it.assignedTickets }
            .filter { ticket ->
                ticket.dueDate != null &&
                    ticket.dueDate < now &&
                    ticket.status != TicketStatus.Done
            }
            .distinctBy { it.id }

        return ProductManagerState(
            outcome = Outcome.blank,
            task = Task.Blank,
            plan = Plan.blank,
            backlogSummary = backlogSummary,
            agentWorkloads = agentWorkloads,
            upcomingDeadlines = upcomingDeadlines,
            blockedTickets = blockedTickets,
            overdueTickets = overdueTickets,
        )
    }

    /**
     * Calls the LLM with the given prompt and returns the response.
     *
     * This uses the agent's configured AI provider and model to generate a response.
     *
     * @param prompt The prompt to send to the LLM
     * @return The LLM's response text
     */
    override fun callLLM(prompt: String): String = runBlocking {
        val systemMessage = "You are a product manager agent analyzing project state and making planning decisions. Respond only with valid JSON."
        val temperature = 0.3
        val maxTokens = 500

        val client = agentConfiguration.aiConfiguration.provider.client
        val model = agentConfiguration.aiConfiguration.model

        val messages = listOf(
            ChatMessage(
                role = ChatRole.System,
                content = systemMessage,
            ),
            ChatMessage(
                role = ChatRole.User,
                content = prompt,
            ),
        )

        val request = ChatCompletionRequest(
            model = model.toClientModelId(),
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
        )

        val completion = client.chatCompletion(request)
        completion.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("No response from LLM")
    }

    /**
     * Calls the LLM with custom parameters.
     */
    private fun callLLMWithParams(
        prompt: String,
        systemMessage: String = "You are a product manager agent analyzing project state and making planning decisions. Respond only with valid JSON.",
        temperature: Double = 0.3,
        maxTokens: Int = 500,
    ): String = runBlocking {
        val client = agentConfiguration.aiConfiguration.provider.client
        val model = agentConfiguration.aiConfiguration.model

        val messages = listOf(
            ChatMessage(
                role = ChatRole.System,
                content = systemMessage,
            ),
            ChatMessage(
                role = ChatRole.User,
                content = prompt,
            ),
        )

        val request = ChatCompletionRequest(
            model = model.toClientModelId(),
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
        )

        val completion = client.chatCompletion(request)
        completion.choices.firstOrNull()?.message?.content
            ?: throw IllegalStateException("No response from LLM")
    }

    /**
     * Evaluates the current perception and generates insights.
     */
    private fun evaluatePerception(perception: Perception<ProductManagerState>): Idea {
        // TODO: Implement proper perception evaluation with LLM
        return Idea.blank
    }

    /**
     * Generates a plan for the given task incorporating ideas.
     */
    private fun generatePlanForTask(task: Task, ideas: List<Idea>): Plan {
        // TODO: Implement proper planning with LLM
        return Plan.ForTask(task)
    }

    /**
     * Executes a task using LLM guidance.
     */
    private fun executeTaskWithLLM(task: Task): Outcome {
        // TODO: Implement proper task execution with LLM
        return Outcome.blank
    }

    /**
     * Executes a tool with LLM-generated parameters.
     */
    private fun executeToolWithLLM(tool: Tool<*>, request: ExecutionRequest<*>): ExecutionOutcome {
        // TODO: Implement proper tool execution with LLM
        return ExecutionOutcome.blank
    }

    /**
     * Evaluates outcomes and generates learnings.
     */
    private fun evaluateOutcomes(outcomes: List<Outcome>): Idea {
        // TODO: Implement proper outcome evaluation with LLM
        return Idea.blank
    }
}

/**
 * Structured insights extracted from past knowledge.
 *
 * This is the "lesson summary" that informs future planning.
 */
private data class PlanningInsights(
    val testFirstSuccessRate: Double = 0.0,
    val testFirstLearnings: String = "",
    val commonFailures: Map<String, String> = emptyMap(),
    val optimalTaskCount: Int? = null,
    val decompositionLearnings: String = "",
)
