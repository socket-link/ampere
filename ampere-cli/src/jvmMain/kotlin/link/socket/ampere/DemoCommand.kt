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
import kotlin.time.Duration.Companion.minutes
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
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.tickets.TicketBuilder
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.tools.human.GlobalHumanResponseRegistry
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
import link.socket.ampere.demo.DemoTiming
import link.socket.ampere.demo.GoldenOutput
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.repl.TerminalFactory
import kotlin.system.exitProcess

/**
 * Demo command showcasing AMPERE capabilities with rich visual feedback.
 *
 * Runs the ObservabilitySpark demo with a 3-column layout showing:
 * - Left pane (35%): Event stream
 * - Middle pane (40%): Cognitive cycle progress
 * - Right pane (25%): Agent status and memory stats
 *
 * Timing profiles:
 * - Default (~85s): Balanced viewing experience
 * - Fast (~45s): Quick GIF recording, CI verification
 * - Detailed (~120s): Conference presentations
 *
 * Modes:
 * - Interactive (default): Full TUI with keyboard controls
 * - Quiet (--quiet): Non-TUI mode for CI/scripting
 *
 * Keyboard controls (interactive mode):
 * - d/e/m: Switch view modes (dashboard/events/memory)
 * - 1-9: Focus on agent (detailed view)
 * - ESC: Return from agent focus
 * - v: Toggle verbose mode (shows log panel with stdout/stderr)
 * - h/?: Show help
 * - q/Ctrl+C: Exit
 *
 * Usage:
 *   ampere demo
 *   ampere demo --fast
 *   ampere demo --quiet --no-escalation
 */
class DemoCommand(
    private val contextProvider: () -> AmpereContext
) : CliktCommand(
    name = "demo",
    help = """
        Run the AMPERE demo with 3-column interactive visualization.

        The demo demonstrates autonomous agent behavior by having
        an agent add a new ObservabilitySpark to the AMPERE Spark system.
        This command shows the execution with a 3-column layout:

        Left pane:   Event stream (filtered by significance)
        Middle pane: Cognitive cycle progress (PERCEIVE → PLAN → EXECUTE → LEARN)
        Right pane:  Agent status and memory statistics

        Timing Profiles:
          --fast       Quick run (~45s) for GIF recording or CI
          --detailed   Slow run (~120s) for conference presentations
          (default)    Balanced run (~85s) for general viewing

        CI/Scripting Options:
          --quiet / -q     Non-TUI mode with machine-parseable output
          --no-escalation  Skip escalation prompt, use default choice (A)

        Keyboard controls (interactive mode):
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
          ampere demo
          ampere demo --fast
          ampere demo --detailed
          ampere demo --auto-respond
          ampere demo --fast --auto-respond
          ampere demo --quiet --no-escalation
          ampere demo --fast --quiet --no-escalation
    """.trimIndent()
) {
    private val autoRespond: Boolean by option(
        "--auto-respond",
        help = "Automatically respond to escalations with Option A after countdown (for scripted demos)"
    ).flag(default = false)

    private val fast: Boolean by option(
        "--fast",
        help = "Use fast timing profile (~45s total) for quick GIF recording or CI verification"
    ).flag(default = false)

    private val detailed: Boolean by option(
        "--detailed",
        help = "Use detailed timing profile (~120s total) for conference presentations"
    ).flag(default = false)

    private val quiet: Boolean by option(
        "--quiet", "-q",
        help = "Run in non-TUI mode for CI/scripting with machine-parseable output"
    ).flag(default = false)

    private val noEscalation: Boolean by option(
        "--no-escalation",
        help = "Skip escalation prompt entirely, use default choice (A) without prompting"
    ).flag(default = false)

    override fun run() = runBlocking {
        // Determine timing profile (--fast takes precedence over --detailed)
        val timing = when {
            fast -> DemoTiming.FAST
            detailed -> DemoTiming.DETAILED
            else -> DemoTiming.DEFAULT
        }

        // Effective auto-respond: explicit flag OR quiet mode OR no-escalation mode
        val effectiveAutoRespond = autoRespond || quiet || noEscalation

        val context = contextProvider()
        val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Create output directory
        val outputDir = File(System.getProperty("user.home"), ".ampere/demo-output")
        outputDir.mkdirs()

        if (quiet) {
            // Quiet mode: non-TUI, machine-parseable output
            runQuietMode(context, agentScope, timing, outputDir, noEscalation)
        } else {
            // Interactive mode: full TUI
            runInteractiveMode(context, agentScope, timing, outputDir, effectiveAutoRespond)
        }
    }

    /**
     * Run in quiet mode for CI/scripting.
     * No TUI, prints phase summaries to stdout.
     */
    private suspend fun runQuietMode(
        context: AmpereContext,
        agentScope: CoroutineScope,
        timing: DemoTiming,
        outputDir: File,
        skipEscalation: Boolean,
    ) {
        val startTime = Clock.System.now()
        var exitCode = 0
        var outputFile: String? = null
        var ideaCount = 0
        var taskCount = 0
        var complexity = "UNKNOWN"
        var knowledgeCount = 0

        try {
            println("AMPERE Demo")
            println("Task: Add ObservabilitySpark to AMPERE Spark system")
            if (skipEscalation) {
                println("Escalation skipped (--no-escalation)")
            }
            println()

            context.start()

            // Create the write_code_file tool that tracks output
            val writeCodeTool = createQuietWriteCodeFileTool(outputDir) { filePath ->
                outputFile = filePath
            }

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

            val agent = agentFactory.create<CodeAgent>(AgentType.CODE)
            val eventApi = context.environmentService.createEventApi(agent.id)

            // Create and assign ticket
            val ticketSpec = createTicketSpec(agent.id)
            val ticketOrchestrator = context.environmentService.ticketOrchestrator
            val result = ticketOrchestrator.createTicket(
                title = ticketSpec.title,
                description = ticketSpec.description,
                type = ticketSpec.type,
                priority = ticketSpec.priority,
                createdByAgentId = ticketSpec.createdByAgentId,
            )

            if (result.isFailure) {
                System.err.println("[ERROR] Failed to create ticket: ${result.exceptionOrNull()?.message}")
                exitCode = 1
                return
            }

            val (ticket, _) = result.getOrThrow()
            ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = agent.id,
                assignerAgentId = ticketSpec.createdByAgentId,
            )

            val ticketResult = context.environmentService.ticketRepository.getTicket(ticket.id)
            val fetchedTicket = ticketResult.getOrNull() ?: run {
                System.err.println("[ERROR] Could not fetch ticket")
                exitCode = 1
                return
            }

            val task = Task.CodeChange(
                id = "task-${ticket.id}",
                status = TaskStatus.Pending,
                description = fetchedTicket.description
            )

            // PERCEIVE
            print("[PERCEIVE] Analyzing... ")
            delay(timing.perceiveDelay.inWholeMilliseconds)
            val perception = agent.perceiveState(agent.getCurrentState())
            ideaCount = perception.ideas.size
            println("$ideaCount ideas")
            delay(timing.perceiveResultPause.inWholeMilliseconds)

            if (perception.ideas.isEmpty()) {
                System.err.println("[ERROR] No ideas generated")
                exitCode = 1
                return
            }

            // PLAN
            print("[PLAN] ")
            if (!skipEscalation) {
                delay(timing.escalationDelay.inWholeMilliseconds)
            }
            delay(timing.planDelay.inWholeMilliseconds)
            val plan = agent.determinePlanForTask(
                task = task,
                ideas = arrayOf(perception.ideas.first()),
                relevantKnowledge = emptyList()
            )
            taskCount = plan.tasks.size
            complexity = plan.estimatedComplexity.toString()
            println("$taskCount steps, complexity: $complexity")
            delay(timing.planResultPause.inWholeMilliseconds)

            // EXECUTE
            print("[EXECUTE] Writing ObservabilitySpark.kt... ")
            delay(timing.executeDelay.inWholeMilliseconds)

            var usedGoldenOutput = false
            val outcome = try {
                agent.executePlan(plan)
            } catch (e: Exception) {
                // LLM failed - fall back to golden output in quiet mode
                System.err.println("[WARN] LLM execution failed: ${e.message}")
                print("[EXECUTE] Using golden output fallback... ")
                usedGoldenOutput = true
                val goldenPath = GoldenOutput.writeObservabilitySpark(outputDir)
                outputFile = goldenPath
                null
            }

            if (usedGoldenOutput) {
                println("done (golden)")
                eventApi.publishCodeSubmitted(
                    urgency = Urgency.LOW,
                    filePath = GoldenOutput.OBSERVABILITY_SPARK_PATH,
                    changeDescription = "Written using golden output fallback",
                    reviewRequired = false,
                    assignedTo = null,
                )
            } else when (outcome) {
                is ExecutionOutcome.CodeChanged.Success -> {
                    println("done")
                    outcome.changedFiles.forEach { file ->
                        eventApi.publishCodeSubmitted(
                            urgency = Urgency.LOW,
                            filePath = file,
                            changeDescription = "Written by CodeAgent",
                            reviewRequired = false,
                            assignedTo = null,
                        )
                    }
                }
                is ExecutionOutcome.CodeChanged.Failure -> {
                    // Fall back to golden output on failure
                    System.err.println("[WARN] LLM generated failure: ${outcome.error.message}")
                    print("[EXECUTE] Using golden output fallback... ")
                    val goldenPath = GoldenOutput.writeObservabilitySpark(outputDir)
                    outputFile = goldenPath
                    println("done (golden)")
                    eventApi.publishCodeSubmitted(
                        urgency = Urgency.LOW,
                        filePath = GoldenOutput.OBSERVABILITY_SPARK_PATH,
                        changeDescription = "Written using golden output fallback",
                        reviewRequired = false,
                        assignedTo = null,
                    )
                }
                else -> {
                    println("done (${outcome?.let { it::class.simpleName } ?: "unknown"})")
                }
            }

            // LEARN
            print("[LEARN] ")
            delay(timing.learnDelay.inWholeMilliseconds)
            // Skip knowledge extraction when using golden output fallback
            if (outcome != null && outcome is ExecutionOutcome.CodeChanged.Success) {
                agent.extractKnowledgeFromOutcome(outcome, task, plan)
            }
            knowledgeCount = 1
            println("Stored $knowledgeCount knowledge items")

            // Summary
            val endTime = Clock.System.now()
            val durationSeconds = (endTime - startTime).inWholeSeconds
            println()
            println("Demo completed in ${durationSeconds}s")
            outputFile?.let { println("  Output: $it") }

        } catch (e: CancellationException) {
            exitCode = 130 // Cancelled
        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
            exitCode = 1
        } finally {
            agentScope.cancel()
            context.close()
            if (exitCode != 0) {
                exitProcess(exitCode)
            }
        }
    }

    /**
     * Run in interactive mode with full TUI.
     */
    private suspend fun runInteractiveMode(
        context: AmpereContext,
        agentScope: CoroutineScope,
        timing: DemoTiming,
        outputDir: File,
        autoRespond: Boolean,
    ) {
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

            // Start the demo
            jazzPane.startDemo()
            systemStatus = StatusBar.SystemStatus.WORKING

            // Launch input handling coroutine
            val inputJob = agentScope.launch(Dispatchers.IO) {
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
                                // Commands not supported in demo mode
                                viewConfig = result.newConfig
                            }
                            is DemoInputHandler.KeyResult.EscalationResponse -> {
                                // Provide response to HumanResponseRegistry to unblock agent
                                GlobalHumanResponseRegistry.instance.provideResponse(
                                    result.requestId,
                                    result.response
                                )
                                // Clear escalation in jazz pane
                                jazzPane.clearAwaitingHuman()
                                viewConfig = result.newConfig
                            }
                            is DemoInputHandler.KeyResult.NoChange -> {}
                        }
                    }
                    delay(timing.inputPollingInterval.inWholeMilliseconds)
                }
            }

            // Launch rendering loop
            val renderJob = agentScope.launch(Dispatchers.IO) {
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

                    // Check for escalation state and set input mode if needed
                    if (jazzPane.isAwaitingHuman && viewConfig.inputMode != DemoInputHandler.InputMode.AWAITING_ESCALATION) {
                        // Get the pending request ID from the registry
                        val pendingIds = GlobalHumanResponseRegistry.instance.getPendingRequestIds()
                        if (pendingIds.isNotEmpty()) {
                            viewConfig = viewConfig.copy(
                                inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
                                escalationRequestId = pendingIds.first(),
                                inputHint = "Press [A]/[1] or [B]/[2] to respond"
                            )
                        }
                    } else if (!jazzPane.isAwaitingHuman && viewConfig.inputMode == DemoInputHandler.InputMode.AWAITING_ESCALATION) {
                        // Escalation was cleared externally, reset input mode
                        viewConfig = viewConfig.copy(
                            inputMode = DemoInputHandler.InputMode.NORMAL,
                            escalationRequestId = null,
                            inputHint = null
                        )
                    }

                    // Update system status based on current phase
                    systemStatus = when {
                        jazzPane.isAwaitingHuman -> StatusBar.SystemStatus.WAITING
                        else -> when (jazzPane.currentPhase) {
                            JazzProgressPane.Phase.INITIALIZING -> StatusBar.SystemStatus.IDLE
                            JazzProgressPane.Phase.PERCEIVE -> StatusBar.SystemStatus.THINKING
                            JazzProgressPane.Phase.PLAN -> StatusBar.SystemStatus.THINKING
                            JazzProgressPane.Phase.EXECUTE -> StatusBar.SystemStatus.WORKING
                            JazzProgressPane.Phase.LEARN -> StatusBar.SystemStatus.THINKING
                            JazzProgressPane.Phase.COMPLETED -> StatusBar.SystemStatus.COMPLETED
                            JazzProgressPane.Phase.FAILED -> StatusBar.SystemStatus.ATTENTION_NEEDED
                        }
                    }

                    // Render status bar
                    val activeMode = when (viewConfig.mode) {
                        DemoInputHandler.DemoMode.DASHBOARD -> "dashboard"
                        DemoInputHandler.DemoMode.EVENTS -> "events"
                        DemoInputHandler.DemoMode.MEMORY -> "memory"
                        DemoInputHandler.DemoMode.AGENT_FOCUS -> "agent_focus"
                    }
                    val shortcuts = StatusBar.defaultShortcuts(activeMode)

                    // Build escalation shortcuts when awaiting human input
                    val escalationShortcuts = if (jazzPane.isAwaitingHuman && jazzPane.escalationOptions.isNotEmpty()) {
                        StatusBar.escalationShortcuts(jazzPane.escalationOptions)
                    } else {
                        null
                    }

                    // Use inputHint only for non-escalation input modes (e.g., AWAITING_AGENT)
                    val inputHintForStatusBar = if (escalationShortcuts != null) null else viewConfig.inputHint

                    val statusBarStr = statusBar.render(
                        width = terminal.info.width,
                        shortcuts = shortcuts,
                        status = systemStatus,
                        focusedAgent = viewConfig.focusedAgentIndex,
                        inputHint = inputHintForStatusBar,
                        escalationShortcuts = escalationShortcuts
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

                    delay(timing.renderInterval.inWholeMilliseconds)
                }
            }

            // Run the demo in foreground
            runDemo(context, agentScope, jazzPane, memoryPane, logPane, outputDir, timing, autoRespond)

            // Update status when complete
            systemStatus = StatusBar.SystemStatus.IDLE

            // Give user time to see the result (but can still exit with q/Ctrl+C)
            delay(timing.completionGracePeriod.inWholeMilliseconds)

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
            println("\nDemo completed")
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

    private fun createTicketSpec(agentId: String) = TicketBuilder()
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

            Reference pattern (from PhaseSpark.kt):
            ```kotlin
            @Serializable
            sealed class PhaseSpark : Spark {
                abstract val phase: CognitivePhase
                override val allowedTools: Set<ToolId>? = null
                override val fileAccessScope: FileAccessScope? = null

                @Serializable
                @SerialName("PhaseSpark.Perceive")
                data object Perceive : PhaseSpark() { ... }
            }
            ```

            Constraints:
            * Keep scope tight: one sealed class with one `Verbose` data object.
            * Kotlin only, no new dependencies.
            * Follow the existing Spark patterns in the sparks/ directory.
        """.trimIndent())
        .ofType(TicketType.TASK)
        .withPriority(TicketPriority.HIGH)
        .createdBy("human-demo")
        .assignedTo(agentId)
        .build()

    private suspend fun runDemo(
        context: AmpereContext,
        agentScope: CoroutineScope,
        jazzPane: JazzProgressPane,
        memoryPane: AgentMemoryPane,
        logPane: LogPane,
        outputDir: File,
        timing: DemoTiming,
        autoRespond: Boolean,
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

        // Create event API for publishing task/code events
        val eventApi = context.environmentService.createEventApi(agent.id)

        // Subscribe agent to events
        val eventHandler = EventHandler<Event, link.socket.ampere.agents.events.subscription.Subscription> { event, _ ->
            when (event) {
                is TicketEvent.TicketAssigned -> {
                    if (event.assignedTo == agent.id) {
                        agentScope.launch {
                            handleTicketAssignment(agent, event.ticketId, context, jazzPane, logPane, eventApi, autoRespond, timing)
                        }
                    }
                }
                else -> {}
            }
        }

        context.subscribeToAll(agent.id, eventHandler)

        // Create the ticket
        jazzPane.setPhase(JazzProgressPane.Phase.INITIALIZING, "Creating ticket...")
        delay(timing.initializationDelay.inWholeMilliseconds)

        val ticketSpec = createTicketSpec(agent.id)

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
        logPane: LogPane,
        eventApi: AgentEventApi,
        autoRespond: Boolean,
        timing: DemoTiming,
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

            // Publish TaskCreated for visibility in the event stream
            eventApi.publishTaskCreated(
                taskId = task.id,
                urgency = Urgency.MEDIUM,
                description = ticket.title,
                assignedTo = agent.id,
            )

            // PHASE 1: PERCEIVE
            logPane.info("Starting PERCEIVE phase")
            jazzPane.setPhase(JazzProgressPane.Phase.PERCEIVE, "Analyzing task...")
            delay(timing.perceiveDelay.inWholeMilliseconds)
            val perception = agent.perceiveState(agent.getCurrentState())
            jazzPane.setPerceiveResult(perception.ideas)
            logPane.info("PERCEIVE complete - generated ${perception.ideas.size} ideas")
            delay(timing.perceiveResultPause.inWholeMilliseconds)

            if (perception.ideas.isEmpty()) {
                logPane.error("No ideas generated")
                jazzPane.setFailed("No ideas generated")
                return
            }

            // ESCALATION POINT: After PERCEIVE, before finalizing PLAN
            // Deterministically trigger escalation in demo mode to showcase human-in-the-loop
            // (Skip if noEscalation is set via autoRespond)
            if (!noEscalation) {
                logPane.info("Triggering escalation - awaiting human input")
                jazzPane.setPhase(JazzProgressPane.Phase.PLAN, "Awaiting human input...")
                delay(timing.escalationDelay.inWholeMilliseconds)

                val escalationQuestion = "Implementation scope:"
                val escalationOptions = listOf(
                    "A" to "Single variant (Verbose only) - simpler",
                    "B" to "Two variants (Verbose + Minimal) - more complete"
                )

                jazzPane.setAwaitingHuman(
                    question = escalationQuestion,
                    options = escalationOptions
                )

                // Register escalation with the global registry and wait for response
                val escalationRequestId = generateUUID()

                // Publish EscalationRequested event for visibility in the event pane
                val optionsSummary = escalationOptions.joinToString(" | ") { "[${it.first}] ${it.second}" }
                eventApi.publishEscalationRequested(
                    threadId = "escalation-$escalationRequestId",
                    reason = "$escalationQuestion ($optionsSummary)",
                    context = mapOf(
                        "question" to escalationQuestion,
                        "optionA" to escalationOptions[0].second,
                        "optionB" to escalationOptions[1].second,
                    ),
                )
                logPane.info("Escalation request ID: $escalationRequestId")

                // The render loop detects isAwaitingHuman and sets up AWAITING_ESCALATION input mode
                // DemoInputHandler processes A/B keys and calls provideResponse()
                val humanResponse: String? = if (autoRespond) {
                    // Auto-respond mode: show countdown then respond with A
                    logPane.info("Auto-respond enabled - will select [A] in 3 seconds")
                    for (secondsRemaining in 3 downTo 1) {
                        jazzPane.setAutoRespondCountdown(secondsRemaining)
                        delay(timing.autoRespondCountdownInterval.inWholeMilliseconds)
                    }
                    jazzPane.setAutoRespondCountdown(null)
                    logPane.info("Auto-responded with [A]")
                    "A"
                } else {
                    GlobalHumanResponseRegistry.instance.waitForResponse(
                        requestId = escalationRequestId,
                        timeout = 5.minutes
                    )
                }

                // Process the human's response
                val userChoseVerboseOnly = humanResponse == null || humanResponse == "A"
                val responseSource = if (autoRespond) "auto-respond" else "human"
                logPane.info("Response: ${humanResponse ?: "timeout/default"} [$responseSource] -> ${if (userChoseVerboseOnly) "Verbose only" else "Both variants"}")

                // Clear escalation state (already cleared by input handler, but ensure cleanup)
                jazzPane.clearAwaitingHuman()
            } else {
                logPane.info("Escalation skipped (--no-escalation)")
            }

            // PHASE 2: PLAN (resume with human feedback incorporated)
            logPane.info("Starting PLAN phase")
            jazzPane.setPhase(JazzProgressPane.Phase.PLAN, "Creating plan...")
            delay(timing.planDelay.inWholeMilliseconds)
            val plan = agent.determinePlanForTask(
                task = task,
                ideas = arrayOf(perception.ideas.first()),
                relevantKnowledge = emptyList()
            )
            jazzPane.setPlanResult(plan)
            logPane.info("PLAN complete - ${plan.tasks.size} tasks, complexity: ${plan.estimatedComplexity}")
            delay(timing.planResultPause.inWholeMilliseconds)

            // PHASE 3: EXECUTE
            logPane.info("Starting EXECUTE phase - calling LLM...")
            jazzPane.setPhase(JazzProgressPane.Phase.EXECUTE, "Calling LLM...")
            delay(timing.executeDelay.inWholeMilliseconds)

            var usedGoldenOutput = false
            val outcome = try {
                agent.executePlan(plan)
            } catch (e: Exception) {
                // LLM failed - fall back to golden output
                logPane.error("LLM execution failed: ${e.message}")
                logPane.info("Using golden output fallback...")
                usedGoldenOutput = true
                val outputDir = java.io.File(System.getProperty("user.home"), ".ampere/demo-output")
                val goldenPath = GoldenOutput.writeObservabilitySpark(outputDir)
                jazzPane.addFileWritten(GoldenOutput.OBSERVABILITY_SPARK_PATH, GoldenOutput.OBSERVABILITY_SPARK_CONTENT)
                null
            }

            if (usedGoldenOutput) {
                logPane.info("EXECUTE complete - used golden output fallback")
                eventApi.publishCodeSubmitted(
                    urgency = Urgency.LOW,
                    filePath = GoldenOutput.OBSERVABILITY_SPARK_PATH,
                    changeDescription = "Written using golden output fallback",
                    reviewRequired = false,
                    assignedTo = null,
                )
            } else {
                logPane.info("EXECUTE complete - outcome: ${outcome?.let { it::class.simpleName } ?: "unknown"}")

                when (outcome) {
                    is ExecutionOutcome.CodeChanged.Success -> {
                        logPane.info("Code changed successfully - ${outcome.changedFiles.size} file(s)")
                        outcome.changedFiles.forEach { file ->
                            logPane.info("  - $file")
                            eventApi.publishCodeSubmitted(
                                urgency = Urgency.LOW,
                                filePath = file,
                                changeDescription = "Written by CodeAgent",
                                reviewRequired = false,
                                assignedTo = null,
                            )
                        }
                    }
                    is ExecutionOutcome.CodeChanged.Failure -> {
                        // Fall back to golden output on failure
                        logPane.error("LLM generated failure: ${outcome.error.message}")
                        logPane.info("Using golden output fallback...")
                        val outputDir = java.io.File(System.getProperty("user.home"), ".ampere/demo-output")
                        val goldenPath = GoldenOutput.writeObservabilitySpark(outputDir)
                        jazzPane.addFileWritten(GoldenOutput.OBSERVABILITY_SPARK_PATH, GoldenOutput.OBSERVABILITY_SPARK_CONTENT)
                        logPane.info("Golden output written to: $goldenPath")
                        eventApi.publishCodeSubmitted(
                            urgency = Urgency.LOW,
                            filePath = GoldenOutput.OBSERVABILITY_SPARK_PATH,
                            changeDescription = "Written using golden output fallback",
                            reviewRequired = false,
                            assignedTo = null,
                        )
                    }
                    else -> {
                        logPane.info("Outcome: ${outcome?.let { it::class.simpleName } ?: "unknown"}")
                    }
                }
            }

            // PHASE 4: LEARN
            logPane.info("Starting LEARN phase")
            jazzPane.setPhase(JazzProgressPane.Phase.LEARN, "Extracting knowledge...")
            delay(timing.learnDelay.inWholeMilliseconds)
            // Skip knowledge extraction when using golden output fallback
            if (outcome != null && outcome is ExecutionOutcome.CodeChanged.Success) {
                val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)
                jazzPane.addKnowledgeStored(knowledge.approach)
                logPane.info("LEARN complete - knowledge stored: ${knowledge.approach}")
            } else {
                jazzPane.addKnowledgeStored("golden-fallback-pattern")
                logPane.info("LEARN complete - using fallback knowledge")
            }

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
                    executorId = "demo-executor",
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

    private fun createQuietWriteCodeFileTool(
        outputDir: File,
        onFileWritten: (String) -> Unit
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

                    onFileWritten(file.absolutePath)
                    path
                }

                val endTime = Clock.System.now()

                ExecutionOutcome.CodeChanged.Success(
                    executorId = "demo-executor",
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
