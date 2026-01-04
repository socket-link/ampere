package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
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
import link.socket.ampere.cli.layout.LogCapture
import link.socket.ampere.cli.layout.LogPane
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
import link.socket.ampere.util.LoggingConfiguration
import link.socket.ampere.util.configureLogging

/**
 * Run agents with active work using the interactive TUI.
 *
 * This command launches agents to work on specific tasks while visualizing
 * their progress in the 3-column TUI. You can:
 * - Set a custom goal for an agent to work on
 * - Run preset demos (like Jazz Test)
 * - Work on GitHub issues (specific or continuous)
 *
 * The TUI provides real-time visualization of:
 * - Left pane: Event stream
 * - Middle pane: Cognitive cycle progress
 * - Right pane: Agent memory stats (or logs in verbose mode)
 *
 * Logging behavior:
 * - DEBUG logging is enabled automatically for this command
 * - All logs are captured from the start (buffered up to 500 entries)
 * - Pressing 'v' toggles log visibility without losing history
 *
 * Controls:
 *   d/e/m - Switch view modes (dashboard/events/memory)
 *   v - Toggle verbose mode (shows/hides log panel)
 *   h/? - Show help
 *   1-9 - Focus on specific agent
 *   q or Ctrl+C - Exit
 */
class RunCommand(
    private val contextProvider: () -> AmpereContext,
) : CliktCommand(
    name = "run",
    help = """
        Run agents with active work using the interactive TUI.

        This command launches agents to work on tasks while visualizing
        their progress in real-time with the 3-column TUI.

        Work Modes:
          --goal <text>      Run agent with custom goal
          --demo <name>      Run preset demo (jazz, etc.)
          --issues           Work on available GitHub issues
          --issue <number>   Work on specific GitHub issue

        Viewing Modes (same as 'start'):
          d - Dashboard: System vitals, agent status
          e - Event Stream: Filtered events
          m - Memory Operations: Knowledge patterns
          v - Toggle verbose mode (show/hide logs)
          1-9 - Agent Focus: Detailed agent view

        Logging:
          DEBUG logging is enabled automatically for this command.
          All logs are captured from startup and buffered (500 entries).
          Press 'v' to show/hide logs without losing history.

        Examples:
          ampere run --goal "Implement FizzBuzz"
          ampere run --demo jazz
          ampere run --issues
          ampere run --issue 42

        Note: Only one work mode can be active at a time.
    """.trimIndent()
) {

    private val goal: String? by option("--goal", "-g", help = "Set an autonomous goal for the agent to work on")

    private val demo: String? by option("--demo", "-d", help = "Run a preset demo (e.g., 'jazz')")

    private val issues: Boolean by option("--issues", help = "Work on available GitHub issues").flag(default = false)

    private val issue: Int? by option("--issue", "-i", help = "Work on specific GitHub issue number").int()

    override fun run() = runBlocking {
        // Validate that only one work mode is specified
        val modesSpecified = listOfNotNull(
            goal?.let { "goal" },
            demo?.let { "demo" },
            if (issues) "issues" else null,
            issue?.let { "issue" }
        )

        if (modesSpecified.isEmpty()) {
            echo("Error: No work mode specified. Use --goal, --demo, --issues, or --issue", err = true)
            echo("Run 'ampere run --help' for usage information", err = true)
            return@runBlocking
        }

        if (modesSpecified.size > 1) {
            echo("Error: Multiple work modes specified: ${modesSpecified.joinToString(", ")}", err = true)
            echo("Please specify only one of: --goal, --demo, --issues, or --issue", err = true)
            return@runBlocking
        }

        val terminal = TerminalFactory.createTerminal()

        // Save original stdout BEFORE redirecting for TUI rendering
        // This allows TUI to render directly to terminal, bypassing log capture
        val originalStdout = System.out

        // Create LogPane FIRST before any other initialization
        val logPane = LogPane(terminal)

        // Start log capture IMMEDIATELY in SILENT mode for BOTH stdout and stderr
        // This must happen before configureLogging() to catch all logs
        // - captureStdout = true: Capture OpenAI/ktor logs that go to stdout
        // - silent = true: Don't echo logs to terminal (only to LogPane)
        // TUI will render using originalStdout directly
        LogCapture.start(logPane, silent = true, captureStdout = true)

        // NOW enable DEBUG logging - all logs from here go to LogPane
        val debugConfig = LoggingConfiguration.Debug
        configureLogging(debugConfig)

        // Add test logs to verify LogCapture is working
        System.err.println("TEST ERROR LOG: LogCapture started")
        logPane.info("DIRECT LOG: LogPane test message")

        // Get context but note: it was created in Main.kt with SilentEventLogger
        // EventBus logs won't appear unless we reconfigure the environment
        val context = contextProvider()

        // Replace the silent EventLogger with a verbose one for run mode
        // This ensures EventBus logs (which are most of the activity) are captured
        context.environmentService.eventBus.setLogger(debugConfig.createEventLogger())
        val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val presenter = WatchPresenter(context.eventRelayService)

        // Create remaining TUI components
        val layout = ThreeColumnLayout(terminal)
        val eventPane = RichEventPane(terminal)
        val jazzPane = JazzProgressPane(terminal)
        val memoryPane = AgentMemoryPane(terminal)
        val statusBar = StatusBar(terminal)
        val inputHandler = DemoInputHandler(terminal)
        val helpRenderer = HelpOverlayRenderer(terminal)

        // Create goal handler
        val goalHandler = GoalHandler(
            context = context,
            agentScope = agentScope,
            progressPane = jazzPane,
            memoryPane = memoryPane,
        )

        var viewConfig = DemoInputHandler.DemoViewConfig()
        var memoryState = AgentMemoryPane.AgentMemoryState()
        var systemStatus = StatusBar.SystemStatus.WORKING
        var lastRenderedOutput: String? = null
        var lastCommandResult: CommandResult? = null

        // Create command executor
        val commandExecutor = CommandExecutor(presenter, goalHandler) {
            // Callback when goal is activated via :goal command
            jazzPane.startDemo()
            systemStatus = StatusBar.SystemStatus.WORKING
            lastRenderedOutput = null
        }

        try {
            // Initialize terminal for full-screen rendering
            initializeTerminal(originalStdout)

            // Start the presenter
            presenter.start()

            // Start the demo visualization
            jazzPane.startDemo()

            // Activate the specified work mode
            when {
                goal != null -> {
                    val activationResult = goalHandler.activateGoal(goal!!)
                    if (activationResult.isFailure) {
                        jazzPane.setFailed("Failed to activate goal: ${activationResult.exceptionOrNull()?.message}")
                        systemStatus = StatusBar.SystemStatus.ATTENTION_NEEDED
                    }
                }
                demo == "jazz" -> {
                    // Run the Jazz demo (Fibonacci task)
                    runJazzDemo(context, agentScope, jazzPane, memoryPane, logPane)
                }
                demo != null -> {
                    jazzPane.setFailed("Unknown demo: $demo. Available demos: jazz")
                    systemStatus = StatusBar.SystemStatus.ATTENTION_NEEDED
                }
                issues -> {
                    // Start continuous issue work
                    context.startAutonomousWork()
                    jazzPane.setPhase(JazzProgressPane.Phase.INITIALIZING, "Starting autonomous issue work...")
                }
                issue != null -> {
                    // Work on specific issue
                    jazzPane.setPhase(JazzProgressPane.Phase.INITIALIZING, "Finding issue #$issue...")
                    agentScope.launch {
                        try {
                            val availableIssues = context.codeAgent.queryAvailableIssues()
                            val targetIssue = availableIssues.find { it.number == issue }
                            if (targetIssue != null) {
                                jazzPane.setPhase(JazzProgressPane.Phase.PERCEIVE, "Working on issue #$issue...")
                                val issueResult = context.codeAgent.workOnIssue(targetIssue)
                                if (issueResult.isSuccess) {
                                    jazzPane.setPhase(JazzProgressPane.Phase.COMPLETED)
                                } else {
                                    jazzPane.setFailed("Failed: ${issueResult.exceptionOrNull()?.message}")
                                }
                            } else {
                                jazzPane.setFailed("Issue #$issue not found or not available")
                            }
                        } catch (e: Exception) {
                            jazzPane.setFailed("Error: ${e.message}")
                        }
                    }
                }
            }

            // Wait a moment for initial events
            delay(500)

            // Launch input handling
            val inputJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val key = inputHandler.readKey()
                    if (key != null) {
                        if (lastCommandResult != null) {
                            lastCommandResult = null
                            lastRenderedOutput = null
                        } else {
                            when (val result = inputHandler.processKey(key, viewConfig)) {
                                is DemoInputHandler.KeyResult.Exit -> {
                                    throw CancellationException("User requested exit")
                                }
                                is DemoInputHandler.KeyResult.ConfigChange -> {
                                    viewConfig = result.newConfig
                                    eventPane.verboseMode = viewConfig.verboseMode

                                    // Just update the view - LogCapture is always running
                                    // to ensure we don't lose any historical logs
                                    lastRenderedOutput = null
                                }
                                is DemoInputHandler.KeyResult.ExecuteCommand -> {
                                    viewConfig = result.newConfig
                                    val cmdResult = commandExecutor.execute(result.command)
                                    when (cmdResult) {
                                        is CommandResult.Quit -> {
                                            throw CancellationException("User requested quit")
                                        }
                                        else -> {
                                            lastCommandResult = cmdResult
                                            lastRenderedOutput = null
                                        }
                                    }
                                }
                                is DemoInputHandler.KeyResult.NoChange -> {}
                            }
                        }
                    }
                    delay(50)
                }
            }

            // Launch rendering
            val renderJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val output = if (lastCommandResult != null) {
                        renderCommandResult(lastCommandResult!!, terminal)
                    } else if (viewConfig.showHelp) {
                        helpRenderer.render()
                    } else {
                        val watchState = presenter.getViewState()
                        eventPane.updateEvents(watchState.recentSignificantEvents)

                        memoryState = updateMemoryState(memoryState, watchState, jazzPane)
                        memoryPane.updateState(memoryState)

                        viewConfig.expandedEventIndex?.let { eventPane.expandEvent(it) }
                            ?: eventPane.collapseEvent()

                        systemStatus = when (jazzPane.currentPhase) {
                            JazzProgressPane.Phase.COMPLETED -> StatusBar.SystemStatus.IDLE
                            JazzProgressPane.Phase.FAILED -> StatusBar.SystemStatus.ATTENTION_NEEDED
                            else -> StatusBar.SystemStatus.WORKING
                        }

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

                        when (viewConfig.mode) {
                            DemoInputHandler.DemoMode.AGENT_FOCUS -> {
                                renderAgentFocusView(terminal, memoryState, watchState, jazzPane, statusBarStr)
                            }
                            else -> {
                                val rightPane = if (viewConfig.verboseMode) logPane else memoryPane
                                layout.render(eventPane, jazzPane, rightPane, statusBarStr)
                            }
                        }
                    }

                    if (output != lastRenderedOutput) {
                        // Use original stdout to render TUI, bypassing LogCapture
                        // This prevents TUI rendering from being captured as logs
                        originalStdout.print(output)
                        originalStdout.flush()
                        lastRenderedOutput = output
                    }

                    delay(250)
                }
            }

            // Wait for completion or user exit
            inputJob.join()
            renderJob.join()

        } catch (e: CancellationException) {
            // Clean shutdown
            throw e
        } catch (e: Exception) {
            originalStdout.print("\u001B[2J\u001B[H")
            originalStdout.println("Error: ${e.message}")
            e.printStackTrace()
            originalStdout.flush()
            throw e
        } finally {
            LogCapture.stop()
            presenter.stop()
            inputHandler.close()
            agentScope.cancel()
            restoreTerminal(originalStdout)
            originalStdout.println("\nAMPERE run completed")
        }
    }

    private fun initializeTerminal(out: java.io.PrintStream) {
        out.print("\u001B[?1049h")
        out.print("\u001B[?25l")
        out.print("\u001B[2J")
        out.print("\u001B[H")
        out.flush()
    }

    private fun restoreTerminal(out: java.io.PrintStream) {
        out.print("\u001B[2J")
        out.print("\u001B[H")
        out.print("\u001B[?25h")
        out.print("\u001B[?1049l")
        out.flush()
    }

    private fun renderCommandResult(result: CommandResult, terminal: Terminal): String {
        return buildString {
            append("\u001B[2J")
            append("\u001B[H")

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
                    append("Exiting...")
                }
            }

            append("\n\n")
            append(terminal.render(dim("Press any key to return")))
        }
    }

    private fun updateMemoryState(
        current: AgentMemoryPane.AgentMemoryState,
        watchState: WatchViewState,
        jazzPane: JazzProgressPane
    ): AgentMemoryPane.AgentMemoryState {
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

        lines.add(terminal.render(bold(TextColors.cyan("AGENT FOCUS: ${memoryState.agentName}"))))
        lines.add("")

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

        lines.add(terminal.render(bold("Memory Operations")))
        lines.add("  Items recalled: ${terminal.render(TextColors.cyan(memoryState.itemsRecalled.toString()))}")
        lines.add("  Items stored: ${terminal.render(TextColors.green(memoryState.itemsStored.toString()))}")
        lines.add("")

        lines.add(terminal.render(bold("Cognitive Cycle Progress")))
        val progress = jazzPane.render(width - 4, 12)
        progress.forEach { line ->
            lines.add("  $line")
        }
        lines.add("")

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

            append("\u001B[${height};1H")
            append(statusBarStr)
            append("\u001B[K")
        }
    }

    private suspend fun runJazzDemo(
        context: AmpereContext,
        agentScope: CoroutineScope,
        jazzPane: JazzProgressPane,
        memoryPane: AgentMemoryPane,
        logPane: LogPane
    ) {
        // Delegate to the existing Jazz demo runner logic
        // This is a simplified version - the full implementation would be
        // copied from JazzDemoCommand.runJazzTest()
        jazzPane.setPhase(JazzProgressPane.Phase.INITIALIZING, "Starting Jazz demo...")

        // TODO: Implement full Jazz demo logic here
        // For now, just show a placeholder
        delay(1000)
        jazzPane.setPhase(JazzProgressPane.Phase.PERCEIVE, "Analyzing Fibonacci task...")
        delay(2000)
        jazzPane.setPhase(JazzProgressPane.Phase.PLAN, "Creating implementation plan...")
        delay(2000)
        jazzPane.setPhase(JazzProgressPane.Phase.EXECUTE, "Writing code...")
        delay(3000)
        jazzPane.setPhase(JazzProgressPane.Phase.LEARN, "Extracting knowledge...")
        delay(1500)
        jazzPane.setPhase(JazzProgressPane.Phase.COMPLETED)
    }
}
