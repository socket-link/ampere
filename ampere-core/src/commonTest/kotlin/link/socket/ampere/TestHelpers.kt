package link.socket.ampere

import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.ProductManagerAgent
import link.socket.ampere.agents.definition.QualityAssuranceAgent
import link.socket.ampere.agents.definition.pm.ProductManagerState
import link.socket.ampere.agents.definition.qa.QualityAssuranceState
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.concept.task.MeetingTask
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.concept.task.TaskId
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.events.tickets.AgentWorkload
import link.socket.ampere.agents.events.tickets.BacklogSummary
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * Test helpers for agent type tests
 */

/** Create a test success outcome */
fun stubSuccessOutcome(): Outcome.Success =
    ExecutionOutcome.NoChanges.Success(
        executorId = "test-executor",
        ticketId = "test-ticket",
        taskId = "test-task",
        executionStartTimestamp = Clock.System.now(),
        executionEndTimestamp = Clock.System.now() + 1.seconds,
        message = "Test success"
    )

/** Create a test failure outcome */
fun stubFailureOutcome(): Outcome.Failure =
    ExecutionOutcome.NoChanges.Failure(
        executorId = "test-executor",
        ticketId = "test-ticket",
        taskId = "test-task",
        executionStartTimestamp = Clock.System.now(),
        executionEndTimestamp = Clock.System.now() + 1.seconds,
        message = "Test failure"
    )

/** Create a test agent configuration */
fun stubAgentConfiguration() = AgentConfiguration(
    agentDefinition = WriteCodeAgent,
    aiConfiguration = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4
    )
)

/** Create a test knowledge entry for FROM_OUTCOME type */
fun stubKnowledgeEntry(
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

val STUB_ID = "stub-id"

fun stubOutcome(): Outcome =
    stubSuccessOutcome()

fun stubTask(
    id: TaskId = STUB_ID,
): Task =
    MeetingTask.AgendaItem(
        id = id,
        title = "Test task",
        description = "Test task",
    )

fun stubPlan(
    task: Task = stubTask()
): Plan.ForTask =
    Plan.ForTask(task)

fun stubBacklogSummary(
    ticketsByStatus: Map<TicketStatus, Int> = mapOf(),
    ticketsByPriority: Map<TicketPriority, Int> = mapOf(),
    ticketsByType: Map<TicketType, Int> = mapOf(),
    blockedCount: Int = 0,
    overdueCount: Int = 0,
): BacklogSummary =
    BacklogSummary(
        totalTickets = ticketsByStatus.size,
        ticketsByStatus = ticketsByStatus,
        ticketsByPriority = ticketsByPriority,
        ticketsByType = ticketsByType,
        blockedCount = blockedCount,
        overdueCount = overdueCount,
    )

fun stubAgentWorkloads(
    vararg workloads: Pair<AgentId, AgentWorkload>,
): Map<AgentId, AgentWorkload> =
    workloads.toList().associate { (agentId, workload) ->
        agentId to workload
    }

fun stubProductManagerState(
    outcome: Outcome = stubOutcome(),
    task: Task = stubTask(),
    plan: Plan = stubPlan(task),
    backlogSummary: BacklogSummary = stubBacklogSummary(),
    workloads: Map<AgentId, AgentWorkload> = stubAgentWorkloads(),
    upcomingDeadlines: List<Ticket> = emptyList(),
    blockedTickets: List<Ticket> = emptyList(),
    overdueTickets: List<Ticket> = emptyList(),
): ProductManagerState =
    ProductManagerState(
        outcome = outcome,
        task = task,
        plan = plan,
        backlogSummary = backlogSummary,
        agentWorkloads = workloads,
        upcomingDeadlines = upcomingDeadlines,
        blockedTickets = blockedTickets,
        overdueTickets = overdueTickets,
    )

fun stubProductManagerAgent(
    ticketOrchestrator: TicketOrchestrator,
    initialState: ProductManagerState = stubProductManagerState(),
    agentConfiguration: AgentConfiguration = stubAgentConfiguration(),
): ProductManagerAgent =
    ProductManagerAgent(
        initialState = initialState,
        agentConfiguration = agentConfiguration,
        ticketOrchestrator = ticketOrchestrator,
        memoryService = null,
    )

fun stubQualityAssuranceState(
    task: Task = stubTask(),
    plan: Plan = stubPlan(task),
    outcome: Outcome = stubOutcome(),
): QualityAssuranceState =
    QualityAssuranceState(
        task = task,
        plan = plan,
        outcome = outcome,
    )

fun stubQualityAssuranceAgent(
    initialState: QualityAssuranceState = stubQualityAssuranceState(),
    agentConfiguration: AgentConfiguration = stubAgentConfiguration(),
): QualityAssuranceAgent =
    QualityAssuranceAgent(
        initialState = initialState,
        agentConfiguration = agentConfiguration,
        memoryService = null,
    )
