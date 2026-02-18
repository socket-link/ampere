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
import link.socket.ampere.cli.layout.AgentFocusPane
import link.socket.ampere.cli.layout.AgentMemoryPane
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.layout.CognitiveProgressPane
import link.socket.ampere.cli.layout.LogCapture
import link.socket.ampere.cli.layout.LogPane
import link.socket.ampere.cli.layout.PaneRenderer
import link.socket.ampere.cli.layout.RichEventPane
import link.socket.ampere.cli.layout.StatusBar
import link.socket.ampere.cli.hybrid.HybridDashboardRenderer
import link.socket.ampere.cli.watch.CommandExecutor
import link.socket.ampere.cli.watch.CommandResult
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.renderer.HelpOverlayRenderer
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcRegistry
import link.socket.ampere.repl.TerminalFactory

/**
 * Root command for the Ampere CLI.
 *
 * Launches the interactive TUI dashboard with animated substrate visualization.
 * Without flags, starts in idle observation mode. With work flags, activates
 * agents to work on goals or issues.
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
class AmpereCommand(
    private val contextProvider: () -> AmpereContext,
) : CliktCommand(
    name = "ampere",
    help = """
        AMPERE - Animated Multi-Agent Prompting Environment

        Usage:
          ampere                              # Start interactive TUI dashboard
          ampere --goal "Implement FizzBuzz"   # Start with active goal
          ampere --issues                     # Work on GitHub issues
          ampere --issue 42                   # Work on specific issue

        Work Modes (optional):
          --goal / -g <text>    Run agent with custom goal
          --issues              Work on available GitHub issues
          --issue / -i <num>    Work on specific GitHub issue
          --auto-work           Autonomous background work on issues

        Arc Configuration:
          --arc / -a <name>     Select arc workflow pattern
          --list-arcs           List available arc configurations
          --use-arc-phases      Use Arc phases (Charge -> Flow -> Pulse)

        TUI Controls:
          d          Dashboard mode
          e          Event stream mode
          m          Memory operations mode
          1-9        Focus on specific agent
          v          Toggle verbose mode
          h  or  ?   Toggle help screen
          :          Command mode
          q          Exit

        Subcommands:
          thread       View and manage conversation threads
          status       System-wide status overview
          outcomes     View execution outcomes and learnings
          knowledge    Query agent knowledge repository
          trace        Trace events with context
          task         Publish task events
          issues       Manage GitHub issues
          work         Autonomous headless work on issues
          respond      Respond to agent input requests
          test         Headless automated tests
    """.trimIndent()
) {

    private val goal: String? by option("--goal", "-g", help = "Goal for the agent to work on")

    private val autoWork by option(
        "--auto-work",
        help = "Start autonomous work mode in background (agents work on issues)"
    ).flag(default = false)

    private val issues: Boolean by option("--issues", help = "Work on available GitHub issues").flag(default = false)

    private val issue: Int? by option("--issue", "-i", help = "Work on specific GitHub issue number").int()

    private val arc: String? by option("--arc", "-a", help = "Select arc workflow pattern (e.g., startup-saas, devops-pipeline)")

    private val listArcs: Boolean by option("--list-arcs", help = "List available arc configurations").flag(default = false)

    private val useArcPhases: Boolean by option(
        "--use-arc-phases",
        help = "Execute goal using Arc phases (Charge -> Flow -> Pulse) instead of direct agent execution"
    ).flag(default = false)

    override fun run() = runBlocking {
        // Handle --list-arcs flag (non-TUI, prints and exits)
        if (listArcs) {
            echo("Available arc configurations:")
            echo("")
            ArcRegistry.list().forEach { arcConfig ->
                echo("  ${arcConfig.name}")
                arcConfig.description?.let { desc ->
                    echo("    $desc")
                }
                echo("    Agents: ${arcConfig.agents.joinToString(" → ") { it.role }}")
                echo("")
            }
            return@runBlocking
        }

        // Validate arc name if specified
        val selectedArc = arc?.let { arcName ->
            val arcConfig = ArcRegistry.get(arcName)
            if (arcConfig == null) {
                val availableArcs = ArcRegistry.list().joinToString(", ") { it.name }
                echo("Error: Unknown arc '$arcName'", err = true)
                echo("Available arcs: $availableArcs", err = true)
                return@runBlocking
            }
            arcConfig
        } ?: ArcRegistry.getDefault()

        // Validate work mode flags (at most one)
        val modesSpecified = listOfNotNull(
            goal?.let { "goal" },
            if (issues) "issues" else null,
            issue?.let { "issue" }
        )

        if (modesSpecified.size > 1) {
            echo("Error: Multiple work modes specified: ${modesSpecified.joinToString(", ")}", err = true)
            echo("Please specify only one of: --goal, --issues, or --issue", err = true)
            return@runBlocking
        }

        val hasActiveWork = modesSpecified.isNotEmpty() || autoWork

        val context = contextProvider()
        val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val terminal = TerminalFactory.createTerminal()
        val presenter = WatchPresenter(context.eventRelayService)

        // Create TUI components
        val hybridRenderer = HybridDashboardRenderer(terminal).also { it.initialize() }
        val eventPane = RichEventPane(terminal)
        val jazzPane = CognitiveProgressPane(terminal)
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
        var systemStatus = if (hasActiveWork) StatusBar.SystemStatus.WORKING else StatusBar.SystemStatus.IDLE
        var lastCommandResult: CommandResult? = null

        // Create command executor with callback when goal is activated
        val commandExecutor = CommandExecutor(presenter, goalHandler) {
            // Callback when goal is activated via :goal command
            jazzPane.startDemo()
            systemStatus = StatusBar.SystemStatus.WORKING
        }

        try {
            // Initialize terminal for full-screen rendering
            initializeTerminal()

            // Start log capture immediately to prevent any output leaking below TUI
            LogCapture.start(logPane)

            // Start the presenter
            presenter.start()

            // Activate work mode (if any)
            if (autoWork) {
                context.startAutonomousWork()
                systemStatus = StatusBar.SystemStatus.WORKING
            }

            when {
                goal != null -> {
                    jazzPane.startDemo()
                    if (useArcPhases) {
                        executeWithArcPhases(
                            goal = goal!!,
                            arcConfig = selectedArc,
                            agentScope = agentScope,
                            jazzPane = jazzPane,
                        ) { status -> systemStatus = status }
                    } else {
                        val effectiveGoal = goal!!
                        val activationResult = goalHandler.activateGoal(effectiveGoal)
                        if (activationResult.isFailure) {
                            jazzPane.setFailed("Failed to activate goal: ${activationResult.exceptionOrNull()?.message}")
                            systemStatus = StatusBar.SystemStatus.ATTENTION_NEEDED
                        }
                    }
                }
                issues -> {
                    jazzPane.startDemo()
                    context.startAutonomousWork()
                    jazzPane.setPhase(CognitiveProgressPane.Phase.INITIALIZING, "Starting autonomous issue work...")
                }
                issue != null -> {
                    jazzPane.startDemo()
                    jazzPane.setPhase(CognitiveProgressPane.Phase.INITIALIZING, "Finding issue #$issue...")
                    agentScope.launch {
                        try {
                            val availableIssues = context.codeAgent.queryAvailableIssues()
                            val targetIssue = availableIssues.find { it.number == issue }
                            if (targetIssue != null) {
                                jazzPane.setPhase(CognitiveProgressPane.Phase.PERCEIVE, "Working on issue #$issue...")
                                val issueResult = context.codeAgent.workOnIssue(targetIssue)
                                if (issueResult.isSuccess) {
                                    jazzPane.setPhase(CognitiveProgressPane.Phase.COMPLETED)
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
                else -> {
                    // No work mode — check config file for goal
                    val configGoal = context.userConfig?.goal
                    if (configGoal != null) {
                        jazzPane.startDemo()
                        systemStatus = StatusBar.SystemStatus.WORKING
                        goalHandler.activateGoal(configGoal)
                    }
                    // Otherwise: idle TUI dashboard
                }
            }

            // Wait a moment for initial events to be processed
            delay(500)

            // Launch input handling in background
            val inputJob = launch(Dispatchers.IO) {
                while (isActive) {
                    val key = inputHandler.readKey()
                    if (key != null) {
                        if (lastCommandResult != null) {
                            lastCommandResult = null
                        } else {
                            when (val result = inputHandler.processKey(key, viewConfig)) {
                                is DemoInputHandler.KeyResult.Exit -> {
                                    throw CancellationException("User requested exit")
                                }
                                is DemoInputHandler.KeyResult.ConfigChange -> {
                                    val wasVerbose = viewConfig.verboseMode
                                    viewConfig = result.newConfig

                                    eventPane.verboseMode = viewConfig.verboseMode

                                    if (viewConfig.verboseMode != wasVerbose) {
                                        val out = LogCapture.getOriginalOut() ?: System.out
                                        out.print("\u001B[2J\u001B[H")
                                        out.flush()
                                    }
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
                                        }
                                    }
                                }
                                is DemoInputHandler.KeyResult.EscalationResponse -> {
                                    viewConfig = result.newConfig
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
                    val output = if (lastCommandResult != null) {
                        renderCommandResult(lastCommandResult!!, terminal)
                    } else if (viewConfig.showHelp) {
                        helpRenderer.render()
                    } else {
                        val watchState = presenter.getViewState()
                        eventPane.updateEvents(watchState.recentSignificantEvents, watchState.agentStates)

                        memoryState = updateMemoryState(memoryState, watchState, jazzPane)
                        memoryPane.updateState(memoryState)

                        viewConfig.expandedEventIndex?.let { eventPane.expandEvent(it) }
                            ?: eventPane.collapseEvent()

                        // Update system status based on current phase
                        systemStatus = when {
                            jazzPane.isAwaitingHuman -> StatusBar.SystemStatus.WAITING
                            else -> when (jazzPane.currentPhase) {
                                CognitiveProgressPane.Phase.INITIALIZING -> {
                                    if (hasActiveWork) StatusBar.SystemStatus.WORKING else StatusBar.SystemStatus.IDLE
                                }
                                CognitiveProgressPane.Phase.PERCEIVE -> StatusBar.SystemStatus.THINKING
                                CognitiveProgressPane.Phase.PLAN -> StatusBar.SystemStatus.THINKING
                                CognitiveProgressPane.Phase.EXECUTE -> StatusBar.SystemStatus.WORKING
                                CognitiveProgressPane.Phase.LEARN -> StatusBar.SystemStatus.THINKING
                                CognitiveProgressPane.Phase.COMPLETED -> StatusBar.SystemStatus.COMPLETED
                                CognitiveProgressPane.Phase.FAILED -> StatusBar.SystemStatus.ATTENTION_NEEDED
                            }
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

                        // Determine right pane based on mode
                        val rightPane: PaneRenderer = when {
                            viewConfig.mode == DemoInputHandler.DemoMode.AGENT_FOCUS -> {
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

                        // Render the hybrid animated layout
                        hybridRenderer.render(
                            leftPane = eventPane,
                            middlePane = jazzPane,
                            rightPane = rightPane,
                            statusBar = statusBarStr,
                            viewState = watchState,
                            deltaSeconds = 0.25f
                        )
                    }

                    // Write every frame (animation state changes even when pane content doesn't)
                    val out = LogCapture.getOriginalOut() ?: System.out
                    out.print(output)
                    out.flush()

                    delay(250) // Render at 4 FPS
                }
            }

            // Wait for jobs (they run until cancellation)
            inputJob.join()
            renderJob.join()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val out = LogCapture.getOriginalOut() ?: System.out
            out.print("\u001B[2J\u001B[H")
            out.println("Error: ${e.message}")
            e.printStackTrace(out)
            out.flush()
            throw e
        } finally {
            LogCapture.stop()
            presenter.stop()
            inputHandler.close()
            agentScope.cancel()
            restoreTerminal()
            println("\nAMPERE stopped")
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
        jazzPane: CognitiveProgressPane
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
            CognitiveProgressPane.Phase.INITIALIZING -> AgentMemoryPane.AgentDisplayState.IDLE
            CognitiveProgressPane.Phase.PERCEIVE -> AgentMemoryPane.AgentDisplayState.THINKING
            CognitiveProgressPane.Phase.PLAN -> AgentMemoryPane.AgentDisplayState.THINKING
            CognitiveProgressPane.Phase.EXECUTE -> AgentMemoryPane.AgentDisplayState.WORKING
            CognitiveProgressPane.Phase.LEARN -> AgentMemoryPane.AgentDisplayState.THINKING
            CognitiveProgressPane.Phase.COMPLETED -> AgentMemoryPane.AgentDisplayState.IDLE
            CognitiveProgressPane.Phase.FAILED -> AgentMemoryPane.AgentDisplayState.IDLE
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

    private fun executeWithArcPhases(
        goal: String,
        arcConfig: ArcConfig,
        agentScope: CoroutineScope,
        jazzPane: CognitiveProgressPane,
        updateStatus: (StatusBar.SystemStatus) -> Unit,
    ) {
        agentScope.launch {
            try {
                val projectDirPath = File(System.getProperty("user.dir")).absolutePath
                val runtime = AmpereRuntime.create(
                    arcConfig = arcConfig,
                    projectDirPath = projectDirPath,
                )

                jazzPane.setPhase(CognitiveProgressPane.Phase.INITIALIZING, "Charging: Analyzing project...")
                updateStatus(StatusBar.SystemStatus.THINKING)

                val chargeResult = runtime.executeChargeOnly(goal)
                jazzPane.setPhase(
                    CognitiveProgressPane.Phase.PERCEIVE,
                    "Project: ${chargeResult.projectContext.projectId}, ${chargeResult.agents.size} agents"
                )

                jazzPane.setPhase(CognitiveProgressPane.Phase.PLAN, "Flow: Executing agent loop...")
                updateStatus(StatusBar.SystemStatus.WORKING)

                val result = runtime.execute(goal)

                jazzPane.setPhase(
                    CognitiveProgressPane.Phase.LEARN,
                    "Pulse: ${result.pulseResult.evaluationReport.goalsCompleted}/${result.pulseResult.evaluationReport.goalsTotal} goals"
                )

                if (result.success) {
                    jazzPane.setPhase(CognitiveProgressPane.Phase.COMPLETED)
                    updateStatus(StatusBar.SystemStatus.COMPLETED)
                } else {
                    jazzPane.setFailed("Arc completed with failures")
                    updateStatus(StatusBar.SystemStatus.ATTENTION_NEEDED)
                }
            } catch (e: Exception) {
                jazzPane.setFailed("Arc execution failed: ${e.message}")
                updateStatus(StatusBar.SystemStatus.ATTENTION_NEEDED)
            }
        }
    }
}
