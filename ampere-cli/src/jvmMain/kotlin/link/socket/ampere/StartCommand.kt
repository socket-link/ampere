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
import link.socket.ampere.cli.watch.CommandExecutor
import link.socket.ampere.cli.watch.CommandResult
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.renderer.HelpOverlayRenderer
import link.socket.ampere.repl.TerminalFactory

/**
 * Start the AMPERE environment with an interactive TUI dashboard.
 *
 * This is the main entry point for observing the AMPERE system.
 * It provides a 3-column interactive TUI with multiple viewing modes:
 * - Dashboard mode (d): System vitals, agent status, recent events
 * - Event stream mode (e): Filtered event stream
 * - Memory ops mode (m): Agent memory and knowledge operations
 * - Agent focus mode (1-9): Focus on specific agents
 * - Verbose toggle (v): Show/hide routine events and logs
 *
 * Controls:
 *   d - Dashboard mode
 *   e - Event stream mode
 *   m - Memory operations mode
 *   v - Toggle verbose mode (shows log panel)
 *   1-9 - Focus on specific agent
 *   : - Command mode (type :help for available commands)
 *   Ctrl+C or q - Exit
 */
class StartCommand(
    private val contextProvider: () -> AmpereContext,
) : CliktCommand(
    name = "start",
    help = """
        Start the AMPERE environment with an interactive TUI dashboard.

        This command launches the 3-column interactive TUI for observing
        the AMPERE system. You can switch between different viewing modes
        using keyboard shortcuts.

        Viewing Modes:
          d - Dashboard: System vitals, agent status, recent events
          e - Event Stream: Filtered stream of significant events
          m - Memory Operations: Knowledge recall/storage patterns
          1-9 - Agent Focus: Detailed view of a specific agent

        Options:
          v - Toggle verbose mode (show/hide logs and routine events)
          h or ? - Show help overlay
          : - Enter command mode (type :help for commands)
          q or Ctrl+C - Exit

        Command Mode Commands:
          :goal <description>  - Start an autonomous agent with a goal
          :agents             - List all active agents
          :ticket <id>        - Show ticket details
          :thread <id>        - Show conversation thread
          :quit               - Exit dashboard

        Examples:
          ampere                       # Start interactive TUI
          ampere start                 # Same as above (explicit)

        Note: To run agents with active work, use the 'run' command:
          ampere run --goal "Implement FizzBuzz"
          ampere run --demo jazz
          ampere run --issues
    """.trimIndent()
) {

    private val autoWork by option(
        "--auto-work",
        help = "Start autonomous work mode in background (agents work on issues)"
    ).flag(default = false)

    override fun run() = runBlocking {
        val context = contextProvider()
        val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val terminal = TerminalFactory.createTerminal()
        val presenter = WatchPresenter(context.eventRelayService)

        // Create TUI components
        val layout = ThreeColumnLayout(terminal)
        val eventPane = RichEventPane(terminal)
        val jazzPane = JazzProgressPane(terminal)
        val memoryPane = AgentMemoryPane(terminal)
        val logPane = LogPane(terminal)
        val agentFocusPane = AgentFocusPane(terminal)
        val statusBar = StatusBar(terminal)
        val inputHandler = DemoInputHandler(terminal)
        val helpRenderer = HelpOverlayRenderer(terminal)

        // Create goal handler with AI configuration from user config (if available)
        val goalHandler = GoalHandler(
            context = context,
            agentScope = agentScope,
            progressPane = jazzPane,
            memoryPane = memoryPane,
            aiConfiguration = context.aiConfiguration,
        )

        var viewConfig = DemoInputHandler.DemoViewConfig()
        var memoryState = AgentMemoryPane.AgentMemoryState()
        var systemStatus = StatusBar.SystemStatus.IDLE
        var lastRenderedOutput: String? = null
        var lastCommandResult: CommandResult? = null

        // Create command executor with callback when goal is activated
        val commandExecutor = CommandExecutor(presenter, goalHandler) {
            // Callback when goal is activated via :goal command
            jazzPane.startDemo()
            systemStatus = StatusBar.SystemStatus.WORKING
            lastRenderedOutput = null  // Force re-render
        }

        try {
            // Initialize terminal for full-screen rendering
            initializeTerminal()

            // Start log capture immediately to prevent any output leaking below TUI
            // Output will only be visible when verbose mode is enabled
            LogCapture.start(logPane)

            // Start the presenter
            presenter.start()

            // If --auto-work flag is set, start autonomous work mode
            if (autoWork) {
                context.startAutonomousWork()
                systemStatus = StatusBar.SystemStatus.WORKING
                // TUI will show work happening in background
            }

            // Wait a moment for initial events to be processed
            delay(500)

            // Launch input handling in background
            val inputJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val key = inputHandler.readKey()
                    if (key != null) {
                        // If showing command result, any key clears it
                        if (lastCommandResult != null) {
                            lastCommandResult = null
                            lastRenderedOutput = null
                        } else {
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

                                    lastRenderedOutput = null  // Force re-render
                                }
                                is DemoInputHandler.KeyResult.ExecuteCommand -> {
                                    viewConfig = result.newConfig
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
                    delay(50) // 20 Hz polling
                }
            }

            // Launch rendering in background
            val renderJob = launch(Dispatchers.IO) {
                while (isActive) {
                    // Check for overlays first
                    val output = if (lastCommandResult != null) {
                        // Show command result overlay
                        renderCommandResult(lastCommandResult!!, terminal)
                    } else if (viewConfig.showHelp) {
                        // Show help overlay
                        helpRenderer.render()
                    } else {
                        // Normal TUI rendering
                        val watchState = presenter.getViewState()
                        eventPane.updateEvents(watchState.recentSignificantEvents)

                        // Update memory state
                        memoryState = updateMemoryState(memoryState, watchState, jazzPane)
                        memoryPane.updateState(memoryState)

                        // Update event pane expanded index
                        viewConfig.expandedEventIndex?.let { eventPane.expandEvent(it) }
                            ?: eventPane.collapseEvent()

                        // Update system status based on current phase
                        systemStatus = when (jazzPane.currentPhase) {
                            JazzProgressPane.Phase.INITIALIZING -> {
                                if (autoWork) StatusBar.SystemStatus.WORKING else StatusBar.SystemStatus.IDLE
                            }
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
                                        cognitiveClusters = emptyList()
                                    )
                                )
                                agentFocusPane
                            }
                            viewConfig.verboseMode -> logPane
                            else -> memoryPane
                        }

                        // Render the 3-column layout
                        layout.render(eventPane, jazzPane, rightPane, statusBarStr)
                    }

                    // Only flush to terminal if output changed
                    if (output != lastRenderedOutput) {
                        // Use original stdout to bypass LogCapture suppression
                        val out = LogCapture.getOriginalOut() ?: System.out
                        out.print(output)
                        out.flush()
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
            // Show error on screen - use original stdout to bypass LogCapture
            val out = LogCapture.getOriginalOut() ?: System.out
            out.print("\u001B[2J\u001B[H")  // Clear screen
            out.println("Error: ${e.message}")
            e.printStackTrace(out)
            out.flush()
            throw e
        } finally {
            LogCapture.stop() // Stop capturing logs
            presenter.stop()
            inputHandler.close()
            agentScope.cancel()
            restoreTerminal()
            println("\nAMPERE TUI stopped")
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
