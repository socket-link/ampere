package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
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
import link.socket.ampere.cli.goal.GoalHandler
import link.socket.ampere.cli.layout.AgentMemoryPane
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.layout.JazzProgressPane
import link.socket.ampere.cli.layout.RichEventPane
import link.socket.ampere.cli.layout.StatusBar
import link.socket.ampere.cli.layout.ThreeColumnLayout
import link.socket.ampere.cli.watch.AgentIndexMap
import link.socket.ampere.cli.watch.CommandExecutor
import link.socket.ampere.cli.watch.CommandResult
import link.socket.ampere.cli.watch.KeyboardInputHandler
import link.socket.ampere.cli.watch.WatchMode
import link.socket.ampere.cli.watch.WatchViewConfig
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.renderer.AgentFocusRenderer
import link.socket.ampere.renderer.DashboardRenderer
import link.socket.ampere.renderer.EventStreamRenderer
import link.socket.ampere.renderer.HelpOverlayRenderer
import link.socket.ampere.renderer.MemoryOpsRenderer
import link.socket.ampere.repl.TerminalFactory

/**
 * Start the AMPERE environment with an interactive multi-modal dashboard.
 *
 * This is the main entry point for observing and interacting with the AMPERE system.
 * It provides multiple viewing modes for observing the environment:
 * - Dashboard mode (d): System vitals, agent status, recent events
 * - Event stream mode (e): Filtered event stream
 * - Memory ops mode (m): Knowledge operations view
 * - Agent focus mode (1-9): Focus on specific agents
 * - Verbose toggle (v): Show/hide routine events
 *
 * Controls:
 *   d - Dashboard mode
 *   e - Event stream mode
 *   m - Memory operations mode
 *   v - Toggle verbose mode
 *   1-9 - Focus on specific agent
 *   Ctrl+C - Exit
 */
class StartCommand(
    private val contextProvider: () -> AmpereContext,
) : CliktCommand(
    name = "start",
    help = """
        Start the AMPERE environment with an interactive dashboard.

        This command launches the multi-modal watch interface, starting in
        dashboard mode. You can switch between different viewing modes using
        keyboard shortcuts:

        Viewing Modes:
          d - Dashboard: System vitals, agent status, recent events
          e - Event Stream: Filtered stream of significant events
          m - Memory Operations: Knowledge recall/storage patterns
          1-9 - Agent Focus: Detailed view of a specific agent

        Options:
          v - Toggle verbose mode (show/hide routine events)
          : - Enter command mode (type :help for commands)
          Ctrl+C - Exit

        Goal Mode:
          Use --goal to start with an autonomous agent working on a task.
          This switches to a 3-column layout showing progress.

        Examples:
          ampere start                           # Start interactive dashboard
          ampere                                 # Same as 'ampere start'
          ampere --goal "Implement FizzBuzz"     # Start with a goal
    """.trimIndent()
) {

    private val goal: String? by option("--goal", "-g", help = "Set an autonomous goal for the agent to work on")

    private val autoWork by option(
        "--auto-work",
        help = "Start autonomous work mode in background"
    ).flag(default = false)

    override fun run() = runBlocking {
        val context = contextProvider()
        val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val terminal = TerminalFactory.createTerminal()
        val presenter = WatchPresenter(context.eventRelayService)

        // Track if we're in goal mode (can change dynamically via :goal command)
        var isGoalMode = goal != null

        // Always create goal-mode components (needed for dynamic :goal activation)
        val layout = ThreeColumnLayout(terminal)
        val eventPane = RichEventPane(terminal)
        val jazzPane = JazzProgressPane(terminal)
        val memoryPane = AgentMemoryPane(terminal)
        val statusBar = StatusBar(terminal)
        val demoInputHandler = DemoInputHandler(terminal)

        // Always create goal handler (needed for :goal command)
        val goalHandler = GoalHandler(
            context = context,
            agentScope = agentScope,
            progressPane = jazzPane,
            memoryPane = memoryPane,
        )

        // Dashboard-mode components
        val renderer = DashboardRenderer(terminal)
        val eventStreamRenderer = EventStreamRenderer(terminal)
        val memoryOpsRenderer = MemoryOpsRenderer(terminal)
        val agentFocusRenderer = AgentFocusRenderer(terminal)
        val helpRenderer = HelpOverlayRenderer(terminal)
        val inputHandler = KeyboardInputHandler(terminal)
        val agentIndexMap = AgentIndexMap()

        var config = WatchViewConfig(mode = WatchMode.DASHBOARD, verboseMode = false)
        var demoConfig = DemoInputHandler.DemoViewConfig()
        var memoryState = AgentMemoryPane.AgentMemoryState()
        var systemStatus = StatusBar.SystemStatus.IDLE
        var lastRenderedOutput: String? = null
        var lastCommandResult: CommandResult? = null
        @Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")
        var previousMode = WatchMode.DASHBOARD

        // Create command executor with callback to switch to goal mode
        val commandExecutor = CommandExecutor(presenter, goalHandler) {
            // Callback when goal is activated via :goal command
            isGoalMode = true
            jazzPane.startDemo()
            systemStatus = StatusBar.SystemStatus.WORKING
            lastRenderedOutput = null  // Force re-render with new layout
        }

        try {
            // Initialize terminal for full-screen rendering
            initializeTerminal()

            // Start the presenter
            presenter.start()

            // If starting with --goal, activate it immediately
            val goalText = goal  // Store in local var for smart cast
            if (goalText != null) {
                jazzPane.startDemo()
                systemStatus = StatusBar.SystemStatus.WORKING
                val activationResult = goalHandler.activateGoal(goalText)
                if (activationResult.isFailure) {
                    jazzPane.setFailed("Failed to activate goal: ${activationResult.exceptionOrNull()?.message}")
                }
            }

            // If --auto-work flag is set, start autonomous work mode
            if (autoWork) {
                context.startAutonomousWork()
                // Dashboard will show work happening in background
            }

            // Wait a moment for initial events to be processed
            delay(500)

            // Launch input handling in background
            val inputJob = launch {
                while (isActive) {
                    if (isGoalMode) {
                        // Goal mode: use demo input handler
                        val key = demoInputHandler.readKey()
                        if (key != null) {
                            // If showing command result, any key clears it
                            if (lastCommandResult != null) {
                                lastCommandResult = null
                                lastRenderedOutput = null
                            } else {
                                when (val result = demoInputHandler.processKey(key, demoConfig)) {
                                    is DemoInputHandler.KeyResult.Exit -> {
                                        throw CancellationException("User requested exit")
                                    }
                                    is DemoInputHandler.KeyResult.ConfigChange -> {
                                        demoConfig = result.newConfig
                                        eventPane.verboseMode = demoConfig.verboseMode
                                        lastRenderedOutput = null  // Force re-render
                                    }
                                    is DemoInputHandler.KeyResult.ExecuteCommand -> {
                                        demoConfig = result.newConfig
                                        // Execute the command
                                        val cmdResult = commandExecutor.execute(result.command)
                                        when (cmdResult) {
                                            is CommandResult.Quit -> {
                                                throw CancellationException("User requested quit")
                                            }
                                            else -> {
                                                lastCommandResult = cmdResult
                                                lastRenderedOutput = null  // Force re-render
                                            }
                                        }
                                    }
                                    is DemoInputHandler.KeyResult.NoChange -> {}
                                }
                            }
                        }
                    } else {
                        // Dashboard mode: use regular input handler
                        val key = inputHandler.readKey()
                        if (key != null) {
                            // If showing command result, any key clears it
                            if (lastCommandResult != null) {
                                lastCommandResult = null
                                lastRenderedOutput = null
                            } else {
                                // Update agent index map before processing keys
                                val viewState = presenter.getViewState()
                                agentIndexMap.update(viewState.agentStates)

                                val newConfig = inputHandler.processKey(key, config, agentIndexMap)

                                // Check for exit signals (Ctrl+C or 'q')
                                if (newConfig == null && (key.code == 3 || key.lowercaseChar() == 'q')) {
                                    throw CancellationException("User requested exit")
                                }

                                if (newConfig != null) {
                                    // Check if we just submitted a command
                                    if (config.mode == WatchMode.COMMAND &&
                                        newConfig.mode == WatchMode.DASHBOARD &&
                                        config.commandInput.isNotEmpty()
                                    ) {
                                        // Execute the command
                                        val result = commandExecutor.execute(config.commandInput)
                                        when (result) {
                                            is CommandResult.Quit -> {
                                                throw CancellationException("User requested quit")
                                            }
                                            else -> {
                                                lastCommandResult = result
                                            }
                                        }
                                    }

                                    // Track previous mode when entering command mode
                                    if (newConfig.mode == WatchMode.COMMAND && config.mode != WatchMode.COMMAND) {
                                        previousMode = config.mode
                                    }

                                    // Check if focused agent is still active
                                    if (newConfig.mode == WatchMode.AGENT_FOCUS &&
                                        newConfig.focusedAgentId != null &&
                                        !agentIndexMap.isAgentActive(newConfig.focusedAgentId)
                                    ) {
                                        config = newConfig.copy(mode = WatchMode.DASHBOARD, focusedAgentId = null)
                                    } else {
                                        config = newConfig
                                    }

                                    // Force re-render on config change
                                    lastRenderedOutput = null
                                }
                            }
                        }
                    }
                    delay(50) // Short delay to prevent busy-waiting
                }
            }

            // Launch rendering in background
            val renderJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val output = if (isGoalMode) {
                        // Goal mode: check for overlays first
                        val commandResult = lastCommandResult
                        if (commandResult != null) {
                            // Show command result overlay
                            renderCommandResult(commandResult, terminal)
                        } else if (demoConfig.showHelp) {
                            // Show help overlay
                            helpRenderer.render()
                        } else {
                            // Normal goal mode rendering
                            val watchState = presenter.getViewState()
                            eventPane.updateEvents(watchState.recentSignificantEvents)

                            // Update memory state
                            memoryState = updateMemoryState(memoryState, watchState, jazzPane)
                            memoryPane.updateState(memoryState)

                            // Update event pane expanded index
                            demoConfig.expandedEventIndex?.let { eventPane.expandEvent(it) }
                                ?: eventPane.collapseEvent()

                            // Update system status based on phase
                            systemStatus = when (jazzPane.currentPhase) {
                                JazzProgressPane.Phase.COMPLETED -> StatusBar.SystemStatus.IDLE
                                JazzProgressPane.Phase.FAILED -> StatusBar.SystemStatus.ATTENTION_NEEDED
                                else -> StatusBar.SystemStatus.WORKING
                            }

                            // Render status bar
                            val activeMode = when (demoConfig.mode) {
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
                                focusedAgent = demoConfig.focusedAgentIndex,
                                inputHint = demoConfig.inputHint
                            )

                            // Render based on mode
                            when (demoConfig.mode) {
                                DemoInputHandler.DemoMode.AGENT_FOCUS -> {
                                    renderAgentFocusView(
                                        terminal, memoryState, watchState, jazzPane, statusBarStr
                                    )
                                }
                                else -> {
                                    layout.render(eventPane, jazzPane, memoryPane, statusBarStr)
                                }
                            }
                        }
                    } else {
                        // Dashboard mode: use original rendering
                        generateOutput(
                            config, presenter, renderer, eventStreamRenderer, memoryOpsRenderer,
                            agentFocusRenderer, helpRenderer, terminal, agentIndexMap, null, lastCommandResult
                        )
                    }

                    // Only flush to terminal if output changed
                    if (output != lastRenderedOutput) {
                        print(output)
                        System.out.flush()
                        lastRenderedOutput = output
                    }

                    delay(250) // Render at 4 FPS
                }
            }

            // Wait for jobs (they run until cancellation)
            inputJob.join()
            renderJob.join()

        } catch (e: CancellationException) {
            // Clean shutdown
            throw e
        } catch (e: Exception) {
            // Show error on screen
            print("\u001B[2J\u001B[H")  // Clear screen
            println("Error: ${e.message}")
            e.printStackTrace()
            System.out.flush()
            throw e
        } finally {
            presenter.stop()
            // Close whichever input handler was active
            if (isGoalMode) {
                demoInputHandler.close()
            } else {
                inputHandler.close()
            }
            agentScope.cancel()
            restoreTerminal()
            println("\nAMPERE dashboard stopped")
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

    private fun generateOutput(
        config: WatchViewConfig,
        presenter: WatchPresenter,
        renderer: DashboardRenderer,
        eventStreamRenderer: EventStreamRenderer,
        memoryOpsRenderer: MemoryOpsRenderer,
        agentFocusRenderer: AgentFocusRenderer,
        helpRenderer: HelpOverlayRenderer,
        terminal: Terminal,
        agentIndexMap: AgentIndexMap,
        lastViewState: WatchViewState?,
        commandResult: CommandResult?
    ): String {
        // If we have a command result to show, render it as an overlay
        if (commandResult != null) {
            return renderCommandResult(commandResult, terminal)
        }

        return if (config.showHelp) {
            helpRenderer.render()
        } else when (config.mode) {
            WatchMode.DASHBOARD -> {
                val viewState = presenter.getViewState()
                renderer.render(viewState, config.verboseMode)
            }
            WatchMode.EVENT_STREAM -> {
                val viewState = presenter.getViewState()
                eventStreamRenderer.render(viewState, config.verboseMode)
            }
            WatchMode.MEMORY_OPS -> {
                val viewState = presenter.getViewState()
                val clusters = presenter.getRecentClusters()
                memoryOpsRenderer.render(viewState, clusters)
            }
            WatchMode.AGENT_FOCUS -> {
                val viewState = presenter.getViewState()
                val clusters = presenter.getRecentClusters()
                val agentIndex = config.focusedAgentId?.let { agentIndexMap.getIndex(it) }
                agentFocusRenderer.render(config.focusedAgentId, viewState, clusters, agentIndex)
            }
            WatchMode.HELP -> {
                helpRenderer.render()
            }
            WatchMode.COMMAND -> {
                val viewState = presenter.getViewState()
                buildString {
                    append(renderer.render(viewState, config.verboseMode))
                    append("\u001B[${terminal.info.height};1H")  // Move to bottom line
                    append("\u001B[2K")  // Clear line
                    append(":${config.commandInput}")
                    append("\u001B[?25h")  // Show cursor for typing
                }
            }
        }
    }

    private fun renderCommandResult(result: CommandResult, terminal: Terminal): String {
        return buildString {
            append("\u001B[2J") // Clear screen
            append("\u001B[H")  // Move cursor to home

            when (result) {
                is CommandResult.Success -> {
                    append(terminal.render(bold(TextColors.cyan("Command Result"))))
                    append("\n\n")
                    append(result.output)
                }
                is CommandResult.Error -> {
                    append(terminal.render(bold(TextColors.red("Command Error"))))
                    append("\n\n")
                    append(result.message)
                }
                is CommandResult.Quit -> {
                    // This shouldn't happen as Quit is handled specially
                    append("Exiting...")
                }
            }

            append("\n\n")
            append(terminal.render(dim("Press any key to return")))
        }
    }

    /**
     * Update memory pane state based on watch state (for goal mode).
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
                    if (event.summaryText.contains("relevance")) {
                        tags.add("recall: ${event.summaryText.take(20)}")
                    }
                }
                event.eventType.contains("KnowledgeStored", ignoreCase = true) -> {
                    stored++
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

        // Build activity history
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
        val trimmedActivity = newActivity.takeLast(20)

        val totalMemory = recalled + stored

        return AgentMemoryPane.AgentMemoryState(
            agentName = "CodeWriter",
            agentState = agentState,
            itemsRecalled = recalled,
            itemsStored = stored,
            totalMemoryItems = totalMemory,
            memoryCapacity = 20,
            recentTags = tags.take(5),
            currentPhase = phase.name.lowercase().replaceFirstChar { it.uppercase() },
            recentActivity = trimmedActivity
        )
    }

    /**
     * Render a full-screen agent focus view (for goal mode).
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

        // Pad to fill screen
        val contentHeight = height - 1
        while (lines.size < contentHeight) {
            lines.add("")
        }

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
}
