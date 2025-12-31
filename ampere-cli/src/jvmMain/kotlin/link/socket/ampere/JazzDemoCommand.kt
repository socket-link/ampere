package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.CodeWriterAgent
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.config.AgentConfiguration
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.tickets.TicketBuilder
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.cli.animation.AnimationTerminal
import link.socket.ampere.cli.layout.AgentMemoryPane
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.layout.JazzProgressPane
import link.socket.ampere.cli.layout.RichEventPane
import link.socket.ampere.cli.layout.StatusBar
import link.socket.ampere.cli.layout.ThreeColumnLayout
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * Jazz Test Demo with 3-column interactive visualization.
 *
 * This demo runs the Jazz Test (autonomous agent writing Fibonacci code)
 * with a 3-column layout showing:
 * - Left pane (30%): Event stream with expandable details
 * - Middle pane (50%): Cognitive cycle progress
 * - Right pane (20%): Agent status and memory stats
 *
 * Keyboard controls:
 * - d/e/m: Switch view modes (dashboard/events/memory)
 * - 1-9: Expand event details
 * - ESC: Collapse expanded event
 * - v: Toggle verbose mode
 * - h/?: Show help
 * - q/Ctrl+C: Exit
 *
 * Usage:
 *   ampere demo jazz
 */
class JazzDemoCommand(
    private val contextProvider: () -> AmpereContext
) : CliktCommand(
    name = "jazz",
    help = """
        Run the Jazz Test demo with 3-column interactive visualization.

        The Jazz Test demonstrates autonomous agent behavior by having
        an agent write a Fibonacci function in Kotlin. This command
        shows the execution with a 3-column layout:

        Left pane:   Event stream (expandable with 1-9 keys)
        Middle pane: Cognitive cycle progress (PERCEIVE → PLAN → EXECUTE → LEARN)
        Right pane:  Agent status and memory statistics

        Keyboard controls:
          d/e/m   - Switch view modes
          1-9     - Expand event details
          ESC     - Collapse expanded event
          v       - Toggle verbose mode
          h/?     - Show help
          q/Ctrl+C - Exit

        The agent autonomously:
        1. PERCEIVE - Analyzes the task and generates insights
        2. PLAN - Creates a concrete execution plan
        3. EXECUTE - Writes the Kotlin code
        4. LEARN - Extracts knowledge from the outcome

        Examples:
          ampere demo jazz
    """.trimIndent()
) {

    override fun run() = runBlocking {
        val context = contextProvider()
        val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val animTerminal = AnimationTerminal(useAlternateBuffer = true)
        val terminal = animTerminal.terminal

        // Create 3-column layout and panes
        val layout = ThreeColumnLayout(terminal)
        val eventPane = RichEventPane(terminal)
        val jazzPane = JazzProgressPane(terminal)
        val memoryPane = AgentMemoryPane(terminal)
        val statusBar = StatusBar(terminal)
        val inputHandler = DemoInputHandler(terminal)

        // Create presenter for event updates
        val presenter = WatchPresenter(context.eventRelayService)

        // Create output directory
        val outputDir = File(System.getProperty("user.home"), ".ampere/jazz-test-output")
        outputDir.mkdirs()

        // Track view configuration
        var viewConfig = DemoInputHandler.DemoViewConfig()
        var memoryState = AgentMemoryPane.AgentMemoryState()
        var systemStatus = StatusBar.SystemStatus.IDLE

        try {
            // Initialize terminal and start services
            animTerminal.initialize()
            context.start()
            presenter.start()

            // Start the jazz demo
            jazzPane.startDemo()
            systemStatus = StatusBar.SystemStatus.WORKING

            // Launch input handling coroutine
            val inputJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val key = inputHandler.readKey()
                    if (key != null) {
                        when (val result = inputHandler.processKey(key, viewConfig)) {
                            is DemoInputHandler.KeyResult.Exit -> {
                                throw CancellationException("User requested exit")
                            }
                            is DemoInputHandler.KeyResult.ConfigChange -> {
                                viewConfig = result.newConfig
                                // Update event pane expansion
                                eventPane.expandedIndex = viewConfig.expandedEventIndex
                                eventPane.verboseMode = viewConfig.verboseMode
                            }
                            is DemoInputHandler.KeyResult.NoChange -> {}
                        }
                    }
                    delay(50) // 20 Hz polling
                }
            }

            // Launch rendering loop
            val renderJob = launch(Dispatchers.IO) {
                while (isActive) {
                    // Update panes with current state
                    val watchState = presenter.getViewState()
                    eventPane.updateEvents(watchState.recentSignificantEvents)

                    // Update memory pane with stats from agent state
                    memoryState = updateMemoryState(memoryState, watchState, jazzPane)
                    memoryPane.updateState(memoryState)

                    // Render status bar
                    val activeMode = when (viewConfig.mode) {
                        DemoInputHandler.DemoMode.DASHBOARD -> "dashboard"
                        DemoInputHandler.DemoMode.EVENTS -> "events"
                        DemoInputHandler.DemoMode.MEMORY -> "memory"
                    }
                    val shortcuts = StatusBar.defaultShortcuts(activeMode)
                    val statusBarStr = statusBar.render(
                        width = terminal.info.width,
                        shortcuts = shortcuts,
                        status = systemStatus,
                        expandedEvent = viewConfig.expandedEventIndex
                    )

                    // Render the 3-column layout
                    val output = layout.render(eventPane, jazzPane, memoryPane, statusBarStr)
                    print(output)
                    System.out.flush()

                    delay(250) // 4 FPS
                }
            }

            // Run the jazz test in foreground
            runJazzTest(context, agentScope, jazzPane, memoryPane, outputDir)

            // Update status when complete
            systemStatus = StatusBar.SystemStatus.IDLE

            // Give user time to see the result (but can still exit with q/Ctrl+C)
            delay(10000)

            renderJob.cancel()
            inputJob.cancel()

        } catch (e: CancellationException) {
            // Clean exit from user input
        } catch (e: Exception) {
            jazzPane.setFailed(e.message ?: "Unknown error")
            systemStatus = StatusBar.SystemStatus.ATTENTION_NEEDED
            delay(3000)
        } finally {
            inputHandler.close()
            presenter.stop()
            agentScope.cancel()
            context.close()
            animTerminal.restore()
            println("\nJazz Demo completed")
        }
    }

    /**
     * Update memory pane state based on watch state.
     */
    private fun updateMemoryState(
        current: AgentMemoryPane.AgentMemoryState,
        watchState: link.socket.ampere.cli.watch.presentation.WatchViewState,
        jazzPane: JazzProgressPane
    ): AgentMemoryPane.AgentMemoryState {
        // Count memory events from summaries
        var recalled = 0
        var stored = 0

        watchState.recentSignificantEvents.forEach { event ->
            when {
                event.eventType.contains("KnowledgeRecalled", ignoreCase = true) -> recalled++
                event.eventType.contains("KnowledgeStored", ignoreCase = true) -> stored++
            }
        }

        // Determine agent state from jazz pane phase
        val phase = jazzPane.currentPhase
        val agentState = when (phase) {
            JazzProgressPane.Phase.INITIALIZING -> AgentMemoryPane.AgentDisplayState.IDLE
            JazzProgressPane.Phase.PERCEIVE -> AgentMemoryPane.AgentDisplayState.THINKING
            JazzProgressPane.Phase.PLAN -> AgentMemoryPane.AgentDisplayState.THINKING
            JazzProgressPane.Phase.EXECUTE -> AgentMemoryPane.AgentDisplayState.WORKING
            JazzProgressPane.Phase.LEARN -> AgentMemoryPane.AgentDisplayState.THINKING
            JazzProgressPane.Phase.COMPLETED -> AgentMemoryPane.AgentDisplayState.IDLE
            JazzProgressPane.Phase.FAILED -> AgentMemoryPane.AgentDisplayState.IDLE
        }

        return AgentMemoryPane.AgentMemoryState(
            agentName = "CodeWriter",
            agentState = agentState,
            itemsRecalled = recalled,
            itemsStored = stored,
            recentTags = emptyList(),
            currentPhase = phase.name.lowercase().replaceFirstChar { it.uppercase() }
        )
    }

    private suspend fun runJazzTest(
        context: AmpereContext,
        agentScope: CoroutineScope,
        jazzPane: JazzProgressPane,
        memoryPane: AgentMemoryPane,
        outputDir: File
    ) {
        // Create the write_code_file tool
        val writeCodeTool = createWriteCodeFileTool(outputDir, jazzPane)

        // Configure the agent
        val agentConfig = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_4
            )
        )

        // Create CodeWriterAgent
        val agent = CodeWriterAgent(
            initialState = AgentState(),
            agentConfiguration = agentConfig,
            toolWriteCodeFile = writeCodeTool,
            coroutineScope = agentScope,
            executor = FunctionExecutor.create(),
            memoryServiceFactory = { agentId -> context.createMemoryService(agentId) }
        )

        // Subscribe agent to events
        val eventHandler = EventHandler<Event, link.socket.ampere.agents.events.subscription.Subscription> { event, _ ->
            when (event) {
                is TicketEvent.TicketAssigned -> {
                    if (event.assignedTo == agent.id) {
                        agentScope.launch {
                            handleTicketAssignment(agent, event.ticketId, context, jazzPane)
                        }
                    }
                }
                else -> {}
            }
        }

        context.subscribeToAll(agent.id, eventHandler)

        // Create the ticket
        jazzPane.setPhase(JazzProgressPane.Phase.INITIALIZING, "Creating ticket...")

        val ticketSpec = TicketBuilder()
            .withTitle("Implement Fibonacci function")
            .withDescription("""
                Create a SINGLE Kotlin file with a Fibonacci function.

                Requirements:
                - Function name: fibonacci
                - Input: n (Int) - the position in the Fibonacci sequence
                - Output: Long - the Fibonacci number at position n
                - Use an efficient iterative approach
                - Handle edge cases (n = 0, n = 1)

                IMPORTANT:
                - Create ONLY ONE file named Fibonacci.kt
                - Do NOT create additional utility files
            """.trimIndent())
            .ofType(TicketType.TASK)
            .withPriority(TicketPriority.HIGH)
            .createdBy("human-jazz-demo")
            .assignedTo(agent.id)
            .build()

        val ticketOrchestrator = context.environmentService.ticketOrchestrator

        val result = ticketOrchestrator.createTicket(
            title = ticketSpec.title,
            description = ticketSpec.description,
            type = ticketSpec.type,
            priority = ticketSpec.priority,
            createdByAgentId = ticketSpec.createdByAgentId,
        )

        if (result.isFailure) {
            jazzPane.setFailed("Failed to create ticket: ${result.exceptionOrNull()?.message}")
            return
        }

        val (ticket, _) = result.getOrThrow()
        jazzPane.setTicketInfo(ticket.id, agent.id)

        // Assign the ticket
        ticketOrchestrator.assignTicket(
            ticketId = ticket.id,
            targetAgentId = agent.id,
            assignerAgentId = ticketSpec.createdByAgentId,
        )

        // Wait for completion (up to 90 seconds)
        var elapsed = 0
        while (elapsed < 90 && jazzPane.render(40, 20).none { it.contains("COMPLETED") || it.contains("FAILED") }) {
            delay(1000)
            elapsed++
        }
    }

    private suspend fun handleTicketAssignment(
        agent: CodeWriterAgent,
        ticketId: String,
        context: AmpereContext,
        jazzPane: JazzProgressPane
    ) {
        try {
            val ticketResult = context.environmentService.ticketRepository.getTicket(ticketId)
            val ticket = ticketResult.getOrNull() ?: run {
                jazzPane.setFailed("Could not fetch ticket")
                return
            }

            val task = link.socket.ampere.agents.domain.concept.task.Task.CodeChange(
                id = "task-$ticketId",
                status = link.socket.ampere.agents.domain.concept.status.TaskStatus.Pending,
                description = ticket.description
            )

            // PHASE 1: PERCEIVE
            jazzPane.setPhase(JazzProgressPane.Phase.PERCEIVE, "Analyzing task...")
            val perception = agent.perceiveState(agent.getCurrentState())
            jazzPane.setPerceiveResult(perception.ideas.size)

            if (perception.ideas.isEmpty()) {
                jazzPane.setFailed("No ideas generated")
                return
            }

            // PHASE 2: PLAN
            jazzPane.setPhase(JazzProgressPane.Phase.PLAN, "Creating plan...")
            val plan = agent.determinePlanForTask(
                task = task,
                ideas = arrayOf(perception.ideas.first()),
                relevantKnowledge = emptyList()
            )
            jazzPane.setPlanResult(plan.tasks.size, plan.estimatedComplexity)

            // PHASE 3: EXECUTE
            jazzPane.setPhase(JazzProgressPane.Phase.EXECUTE, "Calling LLM...")
            val outcome = agent.executePlan(plan)

            when (outcome) {
                is ExecutionOutcome.CodeChanged.Success -> {
                    outcome.changedFiles.forEach { file ->
                        jazzPane.addFileWritten(file)
                    }
                }
                is ExecutionOutcome.CodeChanged.Failure -> {
                    jazzPane.setFailed(outcome.error.message)
                    return
                }
                else -> {}
            }

            // PHASE 4: LEARN
            jazzPane.setPhase(JazzProgressPane.Phase.LEARN, "Extracting knowledge...")
            agent.extractKnowledgeFromOutcome(outcome, task, plan)

            // Complete!
            jazzPane.setPhase(JazzProgressPane.Phase.COMPLETED)

        } catch (e: Exception) {
            jazzPane.setFailed(e.message ?: "Unknown error")
        }
    }

    private fun createWriteCodeFileTool(
        outputDir: File,
        jazzPane: JazzProgressPane
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

                    jazzPane.addFileWritten(path)
                    path
                }

                val endTime = Clock.System.now()

                ExecutionOutcome.CodeChanged.Success(
                    executorId = "jazz-demo-executor",
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
