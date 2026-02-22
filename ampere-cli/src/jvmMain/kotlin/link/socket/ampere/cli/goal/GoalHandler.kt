package link.socket.ampere.cli.goal

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.AmpereContext
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.tickets.create
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.cli.layout.AgentMemoryPane
import link.socket.ampere.cli.layout.CognitiveProgressPane
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.util.randomUUID

/**
 * Orchestrates goal activation, agent creation, and task execution.
 *
 * This handler encapsulates the logic for:
 * - Parsing goal strings into tickets
 * - Creating and configuring CodeAgents
 * - Subscribing agents to ticket events
 * - Running the PROPEL cognitive cycle (Perceive → Plan → Execute → Learn)
 *
 * Used by both CLI (`--goal`) and TUI (`:goal`) entry points.
 */
class GoalHandler(
    private val context: AmpereContext,
    private val agentScope: CoroutineScope,
    private val progressPane: CognitiveProgressPane,
    private val memoryPane: AgentMemoryPane,
    private val outputDir: File = File(System.getProperty("user.home"), ".ampere/goal-output"),
    private val aiConfiguration: AIConfiguration? = null,
) {
    private var currentActivation: GoalActivation? = null
    private var currentAgent: CodeAgent? = null
    private var eventApi: AgentEventApi? = null

    /**
     * Check if there's an active goal being worked on.
     */
    fun hasActiveGoal(): Boolean = currentActivation != null

    /**
     * Get the current goal description, if any.
     */
    fun getCurrentGoalDescription(): String? = currentActivation?.description

    /**
     * Get the current activation info, if any.
     */
    fun getCurrentActivation(): GoalActivation? = currentActivation

    /**
     * Activate a new goal for the agent to work on.
     *
     * This will:
     * 1. Parse the goal description into a ticket specification
     * 2. Create a CodeAgent to work on the goal
     * 3. Subscribe the agent to ticket events
     * 4. Create and assign the ticket
     *
     * The agent will then autonomously work through the PROPEL cycle.
     *
     * @param goalDescription The user-provided goal text
     * @return Result containing the GoalActivation on success
     */
    suspend fun activateGoal(goalDescription: String): Result<GoalActivation> {
        // Ensure output directory exists
        outputDir.mkdirs()

        // Create the write_code_file tool
        val writeCodeTool = createWriteCodeFileTool()

        // Use provided AI configuration or fall back to default
        val effectiveAiConfig = aiConfiguration ?: AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_4
        )

        val agentFactory = AgentFactory(
            scope = agentScope,
            ticketOrchestrator = context.environmentService.ticketOrchestrator,
            memoryServiceFactory = { agentId -> context.createMemoryService(agentId) },
            eventApiFactory = { agentId -> context.environmentService.createEventApi(agentId) },
            aiConfiguration = effectiveAiConfig,
            toolWriteCodeFileOverride = writeCodeTool,
        )

        // Create CodeAgent
        val agent = agentFactory.create<CodeAgent>(AgentType.CODE)
        currentAgent = agent

        // Create event API for this agent to publish events
        eventApi = context.environmentService.createEventApi(agent.id)

        // Parse the goal into a ticket specification
        val ticketSpec = GoalParser.parse(
            goalDescription = goalDescription,
            createdBy = "human-cli",
            assignedTo = agent.id,
        )

        // Subscribe agent to events
        val eventHandler = EventHandler<Event, link.socket.ampere.agents.events.subscription.Subscription> { event, _ ->
            when (event) {
                is TicketEvent.TicketAssigned -> {
                    if (event.assignedTo == agent.id) {
                        agentScope.launch(Dispatchers.IO) {
                            try {
                                handleTicketAssignment(agent, event.ticketId)
                            } catch (e: Exception) {
                                progressPane.setFailed("Exception during cognitive cycle: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        context.subscribeToAll(agent.id, eventHandler)

        // Update progress pane
        progressPane.startDemo()
        progressPane.setPhase(CognitiveProgressPane.Phase.INITIALIZING, "Creating ticket...")

        // Create the ticket
        val ticketOrchestrator = context.environmentService.ticketOrchestrator
        val result = ticketOrchestrator.create(ticketSpec)

        if (result.isFailure) {
            progressPane.setFailed("Failed to create ticket: ${result.exceptionOrNull()?.message}")
            return Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        }

        val (ticket, _) = result.getOrThrow()
        progressPane.setTicketInfo(ticket.id, agent.id)

        // Store activation info
        val activation = GoalActivation(
            ticketId = ticket.id,
            agentId = agent.id,
            description = goalDescription,
            title = ticketSpec.title,
        )
        currentActivation = activation

        return Result.success(activation)
    }

    /**
     * Handle ticket assignment by running the PROPEL cognitive cycle.
     */
    private suspend fun handleTicketAssignment(
        agent: CodeAgent,
        ticketId: String,
    ) {
        val api = eventApi
        try {
            val ticketResult = context.environmentService.ticketRepository.getTicket(ticketId)
            val ticket = ticketResult.getOrNull() ?: run {
                progressPane.setFailed("Could not fetch ticket")
                return
            }

            val task = Task.CodeChange(
                id = "task-$ticketId",
                status = TaskStatus.Pending,
                description = ticket.description
            )

            // Publish task created event
            api?.publishTaskCreated(
                taskId = task.id,
                urgency = Urgency.MEDIUM,
                description = ticket.title,
                assignedTo = agent.id
            )

            // PHASE 1: PERCEIVE
            progressPane.setPhase(CognitiveProgressPane.Phase.PERCEIVE, "Analyzing task...")
            publishPhaseEvent(api, agent.id, ticketId, TicketStatus.InProgress, "Perceive")
            val perception = agent.perceiveState(agent.getCurrentState())
            progressPane.setPerceiveResult(perception.ideas)

            if (perception.ideas.isEmpty()) {
                progressPane.setFailed("No ideas generated")
                return
            }

            // PHASE 2: PLAN
            progressPane.setPhase(CognitiveProgressPane.Phase.PLAN, "Creating plan...")
            publishPhaseEvent(api, agent.id, ticketId, TicketStatus.InProgress, "Plan")
            val plan = agent.determinePlanForTask(
                task = task,
                ideas = arrayOf(perception.ideas.first()),
                relevantKnowledge = emptyList()
            )
            progressPane.setPlanResult(plan)

            // PHASE 3: EXECUTE
            progressPane.setPhase(CognitiveProgressPane.Phase.EXECUTE, "Calling LLM...")
            publishPhaseEvent(api, agent.id, ticketId, TicketStatus.InProgress, "Execute")
            val outcome = agent.executePlan(plan)

            when (outcome) {
                is ExecutionOutcome.CodeChanged.Failure -> {
                    progressPane.setFailed(outcome.error.message)
                    return
                }
                is ExecutionOutcome.CodeChanged.Success -> {
                    // Publish code submitted events for each file
                    outcome.changedFiles.forEach { filePath ->
                        api?.publishCodeSubmitted(
                            urgency = Urgency.LOW,
                            filePath = filePath,
                            changeDescription = "Written by CodeAgent",
                            reviewRequired = false,
                            assignedTo = null
                        )
                    }
                }
                else -> {
                    // Files are already tracked in the write_code_file tool
                }
            }

            // PHASE 4: LEARN
            progressPane.setPhase(CognitiveProgressPane.Phase.LEARN, "Extracting knowledge...")
            publishPhaseEvent(api, agent.id, ticketId, TicketStatus.InProgress, "Learn")
            val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)
            progressPane.addKnowledgeStored(knowledge.approach)

            // Complete!
            progressPane.setPhase(CognitiveProgressPane.Phase.COMPLETED)
            publishPhaseEvent(api, agent.id, ticketId, TicketStatus.Done, "Completed")

        } catch (e: Exception) {
            progressPane.setFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Publish a TicketStatusChanged event to keep the WatchPresenter's agent
     * alive during long-running PROPEL phases. Without these events, the
     * presenter's idle detection removes agents after 30 seconds of silence,
     * leaving the waveform visualization empty.
     */
    private suspend fun publishPhaseEvent(
        api: AgentEventApi?,
        agentId: String,
        ticketId: String,
        status: TicketStatus,
        phaseName: String,
    ) {
        api?.publish(
            TicketEvent.TicketStatusChanged(
                eventId = randomUUID(),
                ticketId = ticketId,
                previousStatus = TicketStatus.InProgress,
                newStatus = status,
                eventSource = EventSource.Agent(agentId),
                timestamp = Clock.System.now(),
                urgency = Urgency.LOW,
            )
        )
    }

    /**
     * Create the write_code_file tool for the agent.
     */
    private fun createWriteCodeFileTool(): Tool<link.socket.ampere.agents.execution.request.ExecutionContext.Code.WriteCode> {
        return FunctionTool(
            id = "write_code_file",
            name = "Write Code File",
            description = "Writes or overwrites code files with the specified content",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                val now = Clock.System.now()

                val changedFiles = withContext(Dispatchers.IO) {
                    request.context.instructionsPerFilePath.map { (path, content) ->
                        val file = File(outputDir, path)
                        file.parentFile?.mkdirs()
                        file.writeText(content)

                        progressPane.addFileWritten(path, content)
                        path
                    }
                }

                val endTime = Clock.System.now()

                ExecutionOutcome.CodeChanged.Success(
                    executorId = "goal-handler-executor",
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
