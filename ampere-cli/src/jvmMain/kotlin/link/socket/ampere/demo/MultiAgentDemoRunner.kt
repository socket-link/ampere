package link.socket.ampere.demo

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.AmpereContext
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.agents.definition.ProjectAgent
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.tickets.TicketBuilder
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.cli.layout.CognitiveProgressPane
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * Multi-agent demo runner that coordinates two agents for the handoff demonstration.
 *
 * This runner implements the multi-agent handoff pattern where:
 * - A coordinator agent (ProjectManager) performs PERCEIVE and PLAN phases
 * - The coordinator delegates the task to a worker agent
 * - A worker agent (CodeWriter) performs EXECUTE and LEARN phases
 *
 * The handoff moment is made visible through TaskAssigned/TaskDelegated events
 * that appear in the event stream with clear agent attribution.
 */
class MultiAgentDemoRunner(
    private val context: AmpereContext,
    private val agentScope: CoroutineScope,
    private val progressPane: CognitiveProgressPane,
    private val timing: DemoTiming,
    private val outputDir: File,
) {

    /**
     * Result of the multi-agent demo execution.
     */
    data class DemoResult(
        val success: Boolean,
        val coordinatorId: String,
        val workerId: String,
        val outputFilePath: String?,
        val errorMessage: String? = null,
    )

    /**
     * Run the multi-agent demo with coordinator and worker agents.
     */
    suspend fun run(): DemoResult {
        // Create output directory
        outputDir.mkdirs()

        // Create coordinator agent (ProjectManager role)
        val coordinatorFactory = AgentFactory(
            scope = agentScope,
            ticketOrchestrator = context.environmentService.ticketOrchestrator,
            memoryServiceFactory = { agentId -> context.createMemoryService(agentId) },
            eventApiFactory = { agentId -> context.environmentService.createEventApi(agentId) },
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_4
            ),
        )
        val coordinator = coordinatorFactory.create<ProjectAgent>(AgentType.PROJECT)
        val coordinatorEventApi = context.environmentService.createEventApi(coordinator.id)

        // Create worker agent (CodeWriter role)
        val writeCodeTool = createWriteCodeFileTool(outputDir)
        val workerFactory = AgentFactory(
            scope = agentScope,
            ticketOrchestrator = context.environmentService.ticketOrchestrator,
            memoryServiceFactory = { agentId -> context.createMemoryService(agentId) },
            eventApiFactory = { agentId -> context.environmentService.createEventApi(agentId) },
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_4
            ),
            toolWriteCodeFileOverride = writeCodeTool,
        )
        val worker = workerFactory.create<CodeAgent>(AgentType.CODE)
        val workerEventApi = context.environmentService.createEventApi(worker.id)

        // Set coordinator and worker info on progress pane
        progressPane.setCoordinatorInfo(coordinator.id)
        progressPane.setWorkerInfo(worker.id)

        // Create the task
        val ticketSpec = createTicketSpec(worker.id)
        val ticketOrchestrator = context.environmentService.ticketOrchestrator
        val ticketResult = ticketOrchestrator.createTicket(
            title = ticketSpec.title,
            description = ticketSpec.description,
            type = ticketSpec.type,
            priority = ticketSpec.priority,
            createdByAgentId = coordinator.id,
        )

        if (ticketResult.isFailure) {
            return DemoResult(
                success = false,
                coordinatorId = coordinator.id,
                workerId = worker.id,
                outputFilePath = null,
                errorMessage = "Failed to create ticket: ${ticketResult.exceptionOrNull()?.message}"
            )
        }

        val (ticket, _) = ticketResult.getOrThrow()
        progressPane.setTicketInfo(ticket.id, coordinator.id)

        // Publish TaskCreated event
        coordinatorEventApi.publishTaskCreated(
            taskId = "task-${ticket.id}",
            urgency = Urgency.MEDIUM,
            description = ticket.title,
            assignedTo = null, // Will be assigned during delegation
        )

        val task = Task.CodeChange(
            id = "task-${ticket.id}",
            status = TaskStatus.Pending,
            description = ticket.description
        )

        try {
            // COORDINATOR: PERCEIVE phase
            progressPane.setPhase(CognitiveProgressPane.Phase.PERCEIVE, "Coordinator analyzing...")
            progressPane.setActiveAgent(coordinator.id)
            delay(timing.coordinatorPerceiveDelay.inWholeMilliseconds)

            val perception = coordinator.perceiveState(coordinator.getCurrentState())
            val ideas = if (perception.ideas.isNotEmpty()) {
                perception.ideas
            } else {
                // Fallback: create a synthetic idea
                listOf(Idea(
                    name = "ObservabilitySpark implementation",
                    description = "Implement ObservabilitySpark following existing Spark patterns"
                ))
            }
            progressPane.setPerceiveResult(ideas)
            delay(timing.perceiveResultPause.inWholeMilliseconds)

            // COORDINATOR: PLAN phase
            progressPane.setPhase(CognitiveProgressPane.Phase.PLAN, "Coordinator planning...")
            delay(timing.coordinatorPlanDelay.inWholeMilliseconds)

            val plan = coordinator.determinePlanForTask(
                task = task,
                ideas = arrayOf(ideas.first()),
                relevantKnowledge = emptyList()
            )
            progressPane.setPlanResult(plan)

            // DELEGATION: Emit TaskAssigned event
            progressPane.setPhase(CognitiveProgressPane.Phase.PLAN, "Delegating to worker...")
            emitTaskDelegatedEvent(
                coordinatorEventApi = coordinatorEventApi,
                coordinatorId = coordinator.id,
                workerId = worker.id,
                taskId = task.id,
            )
            delay(timing.delegationDisplayDelay.inWholeMilliseconds)

            // Assign ticket to worker
            ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = worker.id,
                assignerAgentId = coordinator.id,
            )

            // WORKER: EXECUTE phase
            progressPane.setPhase(CognitiveProgressPane.Phase.EXECUTE, "Worker writing code...")
            progressPane.setActiveAgent(worker.id)
            delay(timing.executeDelay.inWholeMilliseconds)

            var outputFilePath: String? = null
            var usedGoldenOutput = false

            val outcome = try {
                worker.executePlan(plan)
            } catch (e: Exception) {
                // Fallback to golden output
                usedGoldenOutput = true
                val goldenPath = GoldenOutput.writeObservabilitySpark(outputDir)
                outputFilePath = goldenPath
                progressPane.addFileWritten(GoldenOutput.OBSERVABILITY_SPARK_PATH, GoldenOutput.OBSERVABILITY_SPARK_CONTENT)
                null
            }

            if (usedGoldenOutput) {
                workerEventApi.publishCodeSubmitted(
                    urgency = Urgency.LOW,
                    filePath = GoldenOutput.OBSERVABILITY_SPARK_PATH,
                    changeDescription = "Written using golden output fallback",
                    reviewRequired = false,
                    assignedTo = null,
                )
            } else when (outcome) {
                is ExecutionOutcome.CodeChanged.Success -> {
                    outcome.changedFiles.forEach { file ->
                        outputFilePath = File(outputDir, file).absolutePath
                        workerEventApi.publishCodeSubmitted(
                            urgency = Urgency.LOW,
                            filePath = file,
                            changeDescription = "Written by CodeAgent",
                            reviewRequired = false,
                            assignedTo = null,
                        )
                    }
                }
                is ExecutionOutcome.CodeChanged.Failure -> {
                    // Fallback to golden output on failure
                    val goldenPath = GoldenOutput.writeObservabilitySpark(outputDir)
                    outputFilePath = goldenPath
                    progressPane.addFileWritten(GoldenOutput.OBSERVABILITY_SPARK_PATH, GoldenOutput.OBSERVABILITY_SPARK_CONTENT)
                    workerEventApi.publishCodeSubmitted(
                        urgency = Urgency.LOW,
                        filePath = GoldenOutput.OBSERVABILITY_SPARK_PATH,
                        changeDescription = "Written using golden output fallback",
                        reviewRequired = false,
                        assignedTo = null,
                    )
                }
                else -> {
                    // No files written
                }
            }

            // WORKER: LEARN phase
            progressPane.setPhase(CognitiveProgressPane.Phase.LEARN, "Worker extracting knowledge...")
            delay(timing.learnDelay.inWholeMilliseconds)

            if (outcome != null && outcome is ExecutionOutcome.CodeChanged.Success) {
                val knowledge = worker.extractKnowledgeFromOutcome(outcome, task, plan)
                progressPane.addKnowledgeStored(knowledge.approach)
            } else {
                progressPane.addKnowledgeStored("golden-fallback-pattern")
            }

            // Emit TaskCompleted event
            emitTaskCompletedEvent(
                workerEventApi = workerEventApi,
                workerId = worker.id,
                taskId = task.id,
            )

            // Mark complete
            progressPane.setPhase(CognitiveProgressPane.Phase.COMPLETED)

            return DemoResult(
                success = true,
                coordinatorId = coordinator.id,
                workerId = worker.id,
                outputFilePath = outputFilePath,
            )

        } catch (e: Exception) {
            progressPane.setFailed(e.message ?: "Unknown error")
            return DemoResult(
                success = false,
                coordinatorId = coordinator.id,
                workerId = worker.id,
                outputFilePath = null,
                errorMessage = e.message,
            )
        }
    }

    private fun createTicketSpec(workerId: String) = TicketBuilder()
        .withTitle("Add ObservabilitySpark to AMPERE Spark system")
        .withDescription("""
            Create a new Spark type that provides guidance about visibility, monitoring, and progress reporting.

            Requirements:
            * Create `ampere-core/src/commonMain/kotlin/link/socket/ampere/agents/domain/cognition/sparks/ObservabilitySpark.kt` with a sealed class hierarchy.
            * The Spark should guide agents on how to emit events, report progress, and make their work visible.
            * Include a `Verbose` data object variant that encourages detailed status updates and progress emission.
            * Use `@Serializable` and `@SerialName("ObservabilitySpark.Verbose")` for polymorphic serialization.
            * Set `allowedTools` and `fileAccessScope` to `null` (non-restrictive, inherits from parent Sparks).
            * The `promptContribution` should guide the agent to emit frequent status updates.

            Constraints:
            * Keep scope tight: one sealed class with one `Verbose` data object.
            * Kotlin only, no new dependencies.
            * Follow the existing Spark patterns in the sparks/ directory.
        """.trimIndent())
        .ofType(TicketType.TASK)
        .withPriority(TicketPriority.HIGH)
        .createdBy("coordinator-demo")
        .assignedTo(workerId)
        .build()

    private suspend fun emitTaskDelegatedEvent(
        coordinatorEventApi: AgentEventApi,
        coordinatorId: String,
        workerId: String,
        taskId: String,
    ) {
        // Emit TaskAssigned event which maps to TaskDelegated in TeamEventAdapter
        val eventId = generateUUID("evt")
        val event = PlanEvent.TaskAssigned(
            eventId = eventId,
            taskLocalId = taskId,
            issueNumber = null,
            agentId = workerId,
            reasoning = "Delegating code generation task to CodeWriter agent",
            eventSource = EventSource.Agent(coordinatorId),
            timestamp = Clock.System.now(),
            urgency = Urgency.MEDIUM,
        )
        context.environmentService.eventBus.publish(event)
    }

    private suspend fun emitTaskCompletedEvent(
        workerEventApi: AgentEventApi,
        workerId: String,
        taskId: String,
    ) {
        // Publish a code submitted event to signal completion
        workerEventApi.publishCodeSubmitted(
            urgency = Urgency.LOW,
            filePath = GoldenOutput.OBSERVABILITY_SPARK_PATH,
            changeDescription = "Task completed by worker agent",
            reviewRequired = false,
            assignedTo = null,
        )
    }

    private fun createWriteCodeFileTool(
        outputDir: File
    ): Tool<link.socket.ampere.agents.execution.request.ExecutionContext.Code.WriteCode> {
        return FunctionTool(
            id = "write_code_file",
            name = "Write Code File",
            description = "Writes or overwrites code files with the specified content",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                val now = Clock.System.now()

                val changedFiles = request.context.instructionsPerFilePath.map { (path, content) ->
                    val file = File(outputDir, path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)

                    progressPane.addFileWritten(path, content)
                    path
                }

                val endTime = Clock.System.now()

                ExecutionOutcome.CodeChanged.Success(
                    executorId = "multi-agent-demo-executor",
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = endTime,
                    changedFiles = changedFiles,
                    validation = ExecutionResult(
                        codeChanges = null,
                        compilation = null,
                        linting = null,
                        tests = null,
                    ),
                )
            }
        )
    }
}
