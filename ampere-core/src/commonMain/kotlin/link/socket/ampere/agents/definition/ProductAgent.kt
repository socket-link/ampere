package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.util.ioDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.product.PlanningInsights
import link.socket.ampere.agents.definition.product.ProductAgentState
import link.socket.ampere.agents.definition.product.ProductPrompts
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.DefaultTaskFactory
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.AgentWorkload
import link.socket.ampere.agents.events.tickets.BacklogSummary
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/**
 * Product Manager Agent responsible for breaking down features into tasks
 * and coordinating implementation efforts.
 *
 * Enhanced with episodic memory-learns which decomposition strategies
 * lead to successful implementations versus which create confusion or rework.
 *
 * Uses the unified AgentReasoning infrastructure for all cognitive operations.
 */
class ProductAgent(
    override val agentConfiguration: AgentConfiguration,
    private val ticketOrchestrator: TicketOrchestrator,
    private val coroutineScope: CoroutineScope? = null,
    override val initialState: ProductAgentState = ProductAgentState.blank,
    private val executor: Executor = FunctionExecutor.create(),
    memoryServiceFactory: ((AgentId) -> AgentMemoryService)? = null,
    private val eventApiOverride: AgentEventApi? = null,
    private val observabilityScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val agentId: AgentId = generateUUID("ProductManagerAgent"),
) : ObservableAgent<ProductAgentState>(eventApiOverride, observabilityScope) {

    override val id: AgentId = agentId

    override val memoryService: AgentMemoryService? = memoryServiceFactory?.invoke(id)

    override val requiredTools: Set<Tool<*>> = emptySet()

    /**
     * ProductAgent uses INTEGRATIVE cognitive affinity.
     *
     * This shapes the agent to understand how features fit into the product,
     * connect user needs to implementation - ideal for product planning
     * and feature decomposition.
     */
    override val affinity: CognitiveAffinity = CognitiveAffinity.INTEGRATIVE

    // ========================================================================
    // Unified Reasoning - All cognitive logic in one place
    // ========================================================================

    private val reasoning = AgentReasoning.create(agentConfiguration, id) {
        agentRole = "Product Manager"
        availableTools = requiredTools
        this.executor = this@ProductAgent.executor

        perception {
            contextBuilder = { state -> ProductPrompts.perceptionContext(state as ProductAgentState) }
        }

        planning {
            taskFactory = DefaultTaskFactory
            customPrompt = { task, ideas, tools, knowledge ->
                val insights = PlanningInsights.fromKnowledge(knowledge)
                ProductPrompts.planning(task, ideas, insights)
            }
        }

        execution {
            // No custom strategies yet - ready for product-specific tools
        }

        evaluation {
            contextBuilder = { outcomes -> ProductPrompts.outcomeContext(outcomes) }
        }

        knowledge {
            extractor = { outcome, task, plan ->
                extractProductKnowledge(outcome, task, plan)
            }
        }
    }

    // ========================================================================
    // PROPEL Cognitive Functions - Delegate to reasoning infrastructure
    // ========================================================================

    override val runLLMToEvaluatePerception: (perception: Perception<ProductAgentState>) -> Idea =
        { perception ->
            runBlocking(ioDispatcher) {
                withTimeout(60000) {
                    reasoning.evaluatePerception(perception)
                }
            }
        }

    override val runLLMToPlan: (task: Task, ideas: List<Idea>) -> Plan =
        { task, ideas ->
            runBlocking(ioDispatcher) {
                withTimeout(60000) {
                    reasoning.generatePlan(task, ideas)
                }
            }
        }

    override val runLLMToExecuteTask: (task: Task) -> Outcome =
        { task ->
            runBlocking(ioDispatcher) {
                withTimeout(60000) {
                    executeTaskWithReasoning(task)
                }
            }
        }

    override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
        { tool, request ->
            runBlocking(ioDispatcher) {
                withTimeout(60000) {
                    reasoning.executeTool(tool, request)
                }
            }
        }

    override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea =
        { outcomes ->
            runBlocking(ioDispatcher) {
                withTimeout(60000) {
                    reasoning.evaluateOutcomes(outcomes, memoryService).summaryIdea
                }
            }
        }

    override fun extractKnowledgeFromOutcome(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge.FromOutcome = extractProductKnowledge(outcome, task, plan)

    override fun callLLM(prompt: String): String =
        runBlocking(ioDispatcher) {
            withTimeout(60000) {
                reasoning.callLLM(prompt)
            }
        }

    // ========================================================================
    // State Management - Fresh state from TicketOrchestrator
    // ========================================================================

    /**
     * Overrides getCurrentState to return fresh state from ticket orchestrator.
     */
    override fun getCurrentState(): ProductAgentState = runBlocking {
        getUpdatedAgentState()
    }

    /**
     * Overrides perceiveState to fetch fresh state from ticket orchestrator.
     */
    override suspend fun perceiveState(
        currentState: ProductAgentState,
        vararg newIdeas: Idea,
    ): Perception<ProductAgentState> {
        val freshState = getUpdatedAgentState()

        val ideas = mutableListOf<Idea>()
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

        return Perception(
            id = generateUUID(id),
            ideas = ideas,
            currentState = freshState,
            timestamp = Clock.System.now(),
        )
    }

    /**
     * Custom planning that incorporates learned insights from past knowledge.
     */
    override suspend fun determinePlanForTask(
        task: Task,
        vararg ideas: Idea,
        relevantKnowledge: List<KnowledgeWithScore>,
    ): Plan {
        val insights = PlanningInsights.fromKnowledge(relevantKnowledge)
        val planTasks = mutableListOf<Task>()
        var estimatedComplexity = 5

        when (task) {
            is Task.CodeChange -> {
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

                estimatedComplexity = when {
                    insights.commonFailures.isNotEmpty() -> 8
                    insights.testFirstSuccessRate > 0.7 -> 4
                    else -> 5
                }
            }
            else -> {
                planTasks.add(task)
            }
        }

        return Plan.ForTask(
            task = task,
            tasks = planTasks,
            estimatedComplexity = estimatedComplexity,
        )
    }

    // ========================================================================
    // Task Execution
    // ========================================================================

    private suspend fun executeTaskWithReasoning(task: Task): Outcome {
        if (task is Task.Blank) {
            return Outcome.blank
        }

        val plan = reasoning.generatePlan(task, emptyList())
        return reasoning.executePlan(plan) { step, _ ->
            link.socket.ampere.agents.domain.reasoning.StepResult.success(
                description = "Execute step: ${step.id}",
                details = "Step completed",
            )
        }.outcome
    }

    // ========================================================================
    // State Fetching
    // ========================================================================

    private suspend fun getUpdatedAgentState(
        agentIds: List<AgentId> = emptyList(),
        deadlineDaysAhead: Int = 7,
    ): ProductAgentState {
        val backlogSummary = ticketOrchestrator.getBacklogSummary()
            .getOrElse { BacklogSummary.empty() }

        val allTickets = ticketOrchestrator.getAllTickets().getOrElse { emptyList() }
        val discoveredAgentIds = if (agentIds.isEmpty()) {
            allTickets.mapNotNull { it.assignedAgentId }.distinct()
        } else {
            agentIds
        }

        val agentWorkloads = discoveredAgentIds.associateWith { agentId ->
            ticketOrchestrator.getAgentWorkload(agentId)
                .getOrElse { AgentWorkload.empty(agentId) }
        }

        val upcomingDeadlines = ticketOrchestrator.getUpcomingDeadlines(deadlineDaysAhead)
            .getOrElse { emptyList() }

        val blockedTickets = agentWorkloads.values
            .flatMap { it.assignedTickets }
            .filter { it.status == TicketStatus.Blocked }
            .distinctBy { it.id }

        val now = Clock.System.now()
        val overdueTickets = agentWorkloads.values
            .flatMap { it.assignedTickets }
            .filter { ticket ->
                ticket.dueDate != null &&
                    ticket.dueDate < now &&
                    ticket.status != TicketStatus.Done
            }
            .distinctBy { it.id }

        return ProductAgentState(
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

    // ========================================================================
    // Knowledge Extraction
    // ========================================================================

    private fun extractProductKnowledge(
        outcome: Outcome,
        task: Task,
        plan: Plan,
    ): Knowledge.FromOutcome {
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

    companion object Companion {
        val SYSTEM_PROMPT = ProductPrompts.SYSTEM_PROMPT
    }
}
