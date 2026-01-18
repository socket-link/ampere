package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
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
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.AgentFactory
import link.socket.ampere.agents.definition.AgentType
import link.socket.ampere.agents.definition.CodeAgent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.tickets.TicketBuilder
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.cli.layout.AgentFocusPane
import link.socket.ampere.cli.layout.AgentMemoryPane
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.layout.JazzProgressPane
import link.socket.ampere.cli.layout.LogCapture
import link.socket.ampere.cli.layout.LogPane
import link.socket.ampere.cli.layout.PaneRenderer
import link.socket.ampere.cli.layout.RichEventPane
import link.socket.ampere.cli.layout.StatusBar
import link.socket.ampere.cli.layout.ThreeColumnLayout
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.repl.TerminalFactory

/**
 * Jazz Test Demo with 3-column interactive visualization.
 *
 * This demo runs the Jazz Test (autonomous agent writing Fibonacci code)
 * with a 3-column layout showing:
 * - Left pane (35%): Event stream
 * - Middle pane (40%): Cognitive cycle progress
 * - Right pane (25%): Agent status and memory stats
 *
 * Keyboard controls:
 * - d/e/m: Switch view modes (dashboard/events/memory)
 * - 1-9: Focus on agent (detailed view)
 * - ESC: Return from agent focus
 * - v: Toggle verbose mode (shows log panel with stdout/stderr)
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

        Left pane:   Event stream (filtered by significance)
        Middle pane: Cognitive cycle progress (PERCEIVE → PLAN → EXECUTE → LEARN)
        Right pane:  Agent status and memory statistics

        Keyboard controls:
          d/e/m    - Switch view modes
          1-9      - Focus on agent (detailed view)
          ESC      - Return from agent focus
          v        - Toggle verbose mode (shows log panel with stdout/stderr)
          h/?      - Show help
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

        val terminal = TerminalFactory.createTerminal()

        // Create 3-column layout and panes
        val layout = ThreeColumnLayout(terminal)
        val eventPane = RichEventPane(terminal)
        val jazzPane = JazzProgressPane(terminal)
        val memoryPane = AgentMemoryPane(terminal)
        val logPane = LogPane(terminal)
        val agentFocusPane = AgentFocusPane(terminal)
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
            initializeTerminal()
            context.start()
            presenter.start()

            // Start log capture immediately to prevent any output leaking below TUI
            // Output will only be visible when verbose mode is enabled
            LogCapture.start(logPane)

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
                                val wasVerbose = viewConfig.verboseMode
                                viewConfig = result.newConfig

                                // Update event pane verbose mode
                                eventPane.verboseMode = viewConfig.verboseMode

                                // Log capture is always active to prevent leaks
                                // Verbose mode just controls LogPane visibility
                                if (viewConfig.verboseMode != wasVerbose) {
                                    // Clear screen to prevent artifacts when toggling
                                    // Use original stdout to bypass LogCapture
                                    val out = LogCapture.getOriginalOut() ?: System.out
                                    out.print("\u001B[2J\u001B[H")
                                    out.flush()
                                }
                            }
                            is DemoInputHandler.KeyResult.ExecuteCommand -> {
                                // Commands not supported in jazz demo mode
                                viewConfig = result.newConfig
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
                    eventPane.updateEvents(watchState.recentSignificantEvents, watchState.agentStates)

                    // Update memory pane with stats from agent state
                    memoryState = updateMemoryState(memoryState, watchState, jazzPane)
                    memoryPane.updateState(memoryState)

                    // Update event pane expanded index
                    viewConfig.expandedEventIndex?.let { eventPane.expandEvent(it) }
                        ?: eventPane.collapseEvent()

                    // Update system status based on current phase
                    systemStatus = when (jazzPane.currentPhase) {
                        JazzProgressPane.Phase.INITIALIZING -> StatusBar.SystemStatus.IDLE
                        JazzProgressPane.Phase.PERCEIVE -> StatusBar.SystemStatus.THINKING
                        JazzProgressPane.Phase.PLAN -> StatusBar.SystemStatus.THINKING
                        JazzProgressPane.Phase.EXECUTE -> StatusBar.SystemStatus.WORKING
                        JazzProgressPane.Phase.LEARN -> StatusBar.SystemStatus.THINKING
                        JazzProgressPane.Phase.COMPLETED -> StatusBar.SystemStatus.COMPLETED
                        JazzProgressPane.Phase.FAILED -> StatusBar.SystemStatus.ATTENTION_NEEDED
                    }

                    // Render status bar
                    val activeMode = when (viewConfig.mode) {
                        DemoInputHandler.DemoMode.DASHBOARD -> "dashboard"
                        DemoInputHandler.DemoMode.EVENTS -> "events"
                        DemoInputHandler.DemoMode.MEMORY -> "memory"
                        DemoInputHandler.DemoMode.AGENT_FOCUS -> "agent_focus"
                    }
                    val shortcuts = StatusBar.defaultShortcuts(activeMode)
                    val statusBarStr = statusBar.render(
                        width = terminal.info.width,
                        shortcuts = shortcuts,
                        status = systemStatus,
                        focusedAgent = viewConfig.focusedAgentIndex,
                        inputHint = viewConfig.inputHint
                    )

                    // Determine right pane based on mode
                    val rightPane: PaneRenderer = when {
                        viewConfig.mode == DemoInputHandler.DemoMode.AGENT_FOCUS -> {
                            // Update agent focus pane with current state
                            val focusedAgentId = viewConfig.focusedAgentIndex?.let { idx ->
                                watchState.agentStates.keys.elementAtOrNull(idx - 1)
                            }
                                agentFocusPane.updateState(
                                    AgentFocusPane.FocusState(
                                        agentId = focusedAgentId,
                                        agentIndex = viewConfig.focusedAgentIndex,
                                        agentState = focusedAgentId?.let { watchState.agentStates[it] },
                                        recentEvents = watchState.recentSignificantEvents,
                                        cognitiveClusters = emptyList(),
                                        sparkHistory = focusedAgentId?.let { presenter.getSparkHistory(it, 20) }
                                            ?: emptyList()
                                    )
                                )
                            agentFocusPane
                        }
                        viewConfig.verboseMode -> logPane
                        else -> memoryPane
                    }

                    // Render the 3-column layout
                    val output = layout.render(eventPane, jazzPane, rightPane, statusBarStr)
                    // Use original stdout to bypass LogCapture suppression
                    val out = LogCapture.getOriginalOut() ?: System.out
                    out.print(output)
                    out.flush()

                    delay(250) // 4 FPS
                }
            }

            // Run the jazz test in foreground
            runJazzTest(context, agentScope, jazzPane, memoryPane, logPane, outputDir)

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
            LogCapture.stop() // Stop capturing logs
            inputHandler.close()
            presenter.stop()
            agentScope.cancel()
            context.close()
            restoreTerminal()
            println("\nJazz Demo completed")
        }
    }

    private fun initializeTerminal() {
        print("\u001B[?1049h")  // Enter alternate screen buffer
        print("\u001B[?25l")    // Hide cursor
        print("\u001B[2J")      // Clear screen
        print("\u001B[H")       // Move cursor to home
        System.out.flush()
    }

    private fun restoreTerminal() {
        print("\u001B[2J")       // Clear screen
        print("\u001B[H")        // Move cursor to home
        print("\u001B[?25h")     // Show cursor
        print("\u001B[?1049l")   // Exit alternate screen buffer
        System.out.flush()
    }

    /**
     * Update memory pane state based on watch state.
     */
    private fun updateMemoryState(
        current: AgentMemoryPane.AgentMemoryState,
        watchState: WatchViewState,
        jazzPane: JazzProgressPane
    ): AgentMemoryPane.AgentMemoryState {
        // Count memory events from summaries
        var recalled = 0
        var stored = 0
        val tags = mutableListOf<String>()

        watchState.recentSignificantEvents.forEach { event ->
            when {
                event.eventType.contains("KnowledgeRecalled", ignoreCase = true) -> {
                    recalled++
                    // Extract tags from summary if present
                    if (event.summaryText.contains("relevance")) {
                        tags.add("recall: ${event.summaryText.take(20)}")
                    }
                }
                event.eventType.contains("KnowledgeStored", ignoreCase = true) -> {
                    stored++
                    // Extract type from summary
                    if (event.summaryText.startsWith("Stored")) {
                        tags.add(event.summaryText.take(25))
                    }
                }
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

        // Build activity history - append new activity if counts changed
        val newActivity = current.recentActivity.toMutableList()
        if (recalled > current.itemsRecalled) {
            newActivity.add(AgentMemoryPane.MemoryActivity(
                AgentMemoryPane.MemoryOpType.RECALL,
                recalled - current.itemsRecalled
            ))
        }
        if (stored > current.itemsStored) {
            newActivity.add(AgentMemoryPane.MemoryActivity(
                AgentMemoryPane.MemoryOpType.STORE,
                stored - current.itemsStored
            ))
        }
        // Keep only last 20 activities
        val trimmedActivity = newActivity.takeLast(20)

        // Total memory is recalled + stored (simplified model)
        val totalMemory = recalled + stored

        return AgentMemoryPane.AgentMemoryState(
            agentName = "CodeWriter",
            agentState = agentState,
            itemsRecalled = recalled,
            itemsStored = stored,
            totalMemoryItems = totalMemory,
            memoryCapacity = 20,  // Reasonable max for visualization
            recentTags = tags.take(5),
            currentPhase = phase.name.lowercase().replaceFirstChar { it.uppercase() },
            recentActivity = trimmedActivity
        )
    }

    private suspend fun runJazzTest(
        context: AmpereContext,
        agentScope: CoroutineScope,
        jazzPane: JazzProgressPane,
        memoryPane: AgentMemoryPane,
        logPane: LogPane,
        outputDir: File
    ) {
        // Create the write_code_file tool
        val writeCodeTool = createWriteCodeFileTool(outputDir, jazzPane)

        val agentFactory = AgentFactory(
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

        // Create CodeAgent
        val agent = agentFactory.create<CodeAgent>(AgentType.CODE)

        // Subscribe agent to events
        val eventHandler = EventHandler<Event, link.socket.ampere.agents.events.subscription.Subscription> { event, _ ->
            when (event) {
                is TicketEvent.TicketAssigned -> {
                    if (event.assignedTo == agent.id) {
                        agentScope.launch {
                            handleTicketAssignment(agent, event.ticketId, context, jazzPane, logPane)
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
        agent: CodeAgent,
        ticketId: String,
        context: AmpereContext,
        jazzPane: JazzProgressPane,
        logPane: LogPane
    ) {
        try {
            logPane.info("Ticket assigned: $ticketId")
            logPane.info("Starting cognitive cycle...")

            val ticketResult = context.environmentService.ticketRepository.getTicket(ticketId)
            val ticket = ticketResult.getOrNull() ?: run {
                logPane.error("Could not fetch ticket")
                jazzPane.setFailed("Could not fetch ticket")
                return
            }

            val task = Task.CodeChange(
                id = "task-$ticketId",
                status = TaskStatus.Pending,
                description = ticket.description
            )
            logPane.info("Task created: ${task.id}")

            // PHASE 1: PERCEIVE
            logPane.info("Starting PERCEIVE phase")
            jazzPane.setPhase(JazzProgressPane.Phase.PERCEIVE, "Analyzing task...")
            val perception = agent.perceiveState(agent.getCurrentState())
            jazzPane.setPerceiveResult(perception.ideas)
            logPane.info("PERCEIVE complete - generated ${perception.ideas.size} ideas")

            if (perception.ideas.isEmpty()) {
                logPane.error("No ideas generated")
                jazzPane.setFailed("No ideas generated")
                return
            }

            // PHASE 2: PLAN
            logPane.info("Starting PLAN phase")
            jazzPane.setPhase(JazzProgressPane.Phase.PLAN, "Creating plan...")
            val plan = agent.determinePlanForTask(
                task = task,
                ideas = arrayOf(perception.ideas.first()),
                relevantKnowledge = emptyList()
            )
            jazzPane.setPlanResult(plan)
            logPane.info("PLAN complete - ${plan.tasks.size} tasks, complexity: ${plan.estimatedComplexity}")

            // PHASE 3: EXECUTE
            logPane.info("Starting EXECUTE phase - calling LLM...")
            jazzPane.setPhase(JazzProgressPane.Phase.EXECUTE, "Calling LLM...")
            val outcome = agent.executePlan(plan)
            logPane.info("EXECUTE complete - outcome: ${outcome::class.simpleName}")

            when (outcome) {
                is ExecutionOutcome.CodeChanged.Success -> {
                    logPane.info("Code changed successfully - ${outcome.changedFiles.size} file(s)")
                    outcome.changedFiles.forEach { file ->
                        logPane.info("  - $file")
                    }
                }
                is ExecutionOutcome.CodeChanged.Failure -> {
                    logPane.error("Code change FAILED: ${outcome.error.message}")
                    jazzPane.setFailed(outcome.error.message)
                    return
                }
                else -> {
                    logPane.info("Outcome: ${outcome::class.simpleName}")
                }
            }

            // PHASE 4: LEARN
            logPane.info("Starting LEARN phase")
            jazzPane.setPhase(JazzProgressPane.Phase.LEARN, "Extracting knowledge...")
            val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)
            jazzPane.addKnowledgeStored(knowledge.approach)
            logPane.info("LEARN complete - knowledge stored: ${knowledge.approach}")

            // Complete!
            logPane.info("Cognitive cycle COMPLETED")
            jazzPane.setPhase(JazzProgressPane.Phase.COMPLETED)

        } catch (e: Exception) {
            logPane.error("Exception: ${e.message}")
            logPane.error("Stack trace: ${e.stackTraceToString()}")
            jazzPane.setFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Render a full-screen agent focus view.
     */
    private fun renderAgentFocusView(
        terminal: Terminal,
        memoryState: AgentMemoryPane.AgentMemoryState,
        watchState: WatchViewState,
        jazzPane: JazzProgressPane,
        statusBarStr: String
    ): String {
        val width = terminal.info.width
        val height = terminal.info.height

        // Build all lines first
        val lines = mutableListOf<String>()

        // Header
        lines.add(terminal.render(bold(TextColors.cyan("AGENT FOCUS: ${memoryState.agentName}"))))
        lines.add("")

        // Agent state section
        lines.add(terminal.render(bold("Current State")))

        val stateColor = when (memoryState.agentState) {
            AgentMemoryPane.AgentDisplayState.WORKING -> TextColors.green
            AgentMemoryPane.AgentDisplayState.THINKING -> TextColors.yellow
            AgentMemoryPane.AgentDisplayState.IDLE -> TextColors.gray
            AgentMemoryPane.AgentDisplayState.WAITING -> TextColors.yellow
            AgentMemoryPane.AgentDisplayState.IN_MEETING -> TextColors.blue
        }

        lines.add("  Status: ${terminal.render(stateColor(memoryState.agentState.name.lowercase()))}")

        memoryState.currentPhase?.let { phase ->
            lines.add("  Phase: ${terminal.render(TextColors.cyan(phase))}")
        }
        lines.add("")

        // Memory statistics
        lines.add(terminal.render(bold("Memory Operations")))
        lines.add("  Items recalled: ${terminal.render(TextColors.cyan(memoryState.itemsRecalled.toString()))}")
        lines.add("  Items stored: ${terminal.render(TextColors.green(memoryState.itemsStored.toString()))}")
        lines.add("")

        // Progress details from jazz pane
        lines.add(terminal.render(bold("Cognitive Cycle Progress")))
        val progress = jazzPane.render(width - 4, 12)
        progress.forEach { line ->
            lines.add("  $line")
        }
        lines.add("")

        // Recent events from this agent
        lines.add(terminal.render(bold("Recent Activity")))
        val agentEvents = watchState.recentSignificantEvents
            .filter { it.sourceAgentName == memoryState.agentName }
            .take(8)

        if (agentEvents.isEmpty()) {
            lines.add(terminal.render(dim("  No recent events")))
        } else {
            agentEvents.forEach { event ->
                val eventColor = when (event.significance) {
                    EventSignificance.CRITICAL -> TextColors.red
                    EventSignificance.SIGNIFICANT -> TextColors.white
                    EventSignificance.ROUTINE -> TextColors.gray
                }
                lines.add("  • ${terminal.render(eventColor(event.summaryText.take(width - 6)))}")
            }
        }

        // Pad to fill screen (minus status bar)
        val contentHeight = height - 1
        while (lines.size < contentHeight) {
            lines.add("")
        }

        // Build output with explicit cursor positioning
        return buildString {
            for (i in 0 until contentHeight) {
                val row = i + 1
                append("\u001B[${row};1H")
                append(lines.getOrElse(i) { "" })
                append("\u001B[K")
            }

            // Status bar at bottom
            append("\u001B[${height};1H")
            append(statusBarStr)
            append("\u001B[K")
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

                    jazzPane.addFileWritten(path, content)
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
