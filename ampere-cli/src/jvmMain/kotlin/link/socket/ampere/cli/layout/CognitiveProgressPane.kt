package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.cli.animation.LightningAnimator

/**
 * Renders the cognitive cycle execution progress as a pane.
 *
 * Displays the cognitive cycle phases (PERCEIVE, PLAN, EXECUTE, LEARN)
 * with progress indicators and real-time status updates.
 */
class CognitiveProgressPane(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System
) : PaneRenderer {

    private var frameCounter: Long = 0
    private val dischargeSequence = LightningAnimator.COMPACT_SEQUENCE

    /**
     * Current phase in the cognitive cycle.
     */
    enum class Phase {
        INITIALIZING,
        PERCEIVE,
        PLAN,
        EXECUTE,
        LEARN,
        COMPLETED,
        FAILED
    }

    /**
     * Information about a file written during execution.
     */
    data class FileWriteInfo(
        val path: String,
        val lineCount: Int = 0,
        val operationType: String = "created"
    )

    /**
     * Escalation information for human-in-the-loop prompts.
     */
    data class EscalationInfo(
        val question: String,
        val options: List<EscalationOption>,
        val startTime: Instant,
        val autoRespondSecondsRemaining: Int? = null
    )

    /**
     * A single escalation option that the human can select.
     */
    data class EscalationOption(
        val key: String,
        val label: String
    )

    /**
     * Current state of the cognitive cycle execution.
     */
    data class CognitiveState(
        val phase: Phase = Phase.INITIALIZING,
        val startTime: Instant? = null,
        val phaseStartTime: Instant? = null,
        val ticketId: String? = null,
        val agentId: String? = null,

        // Multi-agent coordination
        val coordinatorId: String? = null,
        val workerId: String? = null,
        val activeAgentId: String? = null,

        // PERCEIVE outputs
        val ideasGenerated: Int = 0,
        val ideaNames: List<String> = emptyList(),

        // PLAN outputs
        val planSteps: Int = 0,
        val estimatedComplexity: Int = 0,
        val planApproach: String = "",

        // EXECUTE outputs
        val filesWritten: List<FileWriteInfo> = emptyList(),

        // LEARN outputs
        val knowledgeStored: List<String> = emptyList(),

        val errorMessage: String? = null,
        val phaseDetails: String = "",

        // Escalation state (human-in-the-loop)
        val escalation: EscalationInfo? = null
    ) {
        /**
         * Whether we're currently awaiting human input.
         */
        val isAwaitingHuman: Boolean get() = escalation != null

        /**
         * Whether this is a multi-agent execution (has both coordinator and worker).
         */
        val isMultiAgent: Boolean get() = coordinatorId != null && workerId != null
    }

    private var state = CognitiveState()

    /**
     * Get the current phase for external observation.
     */
    val currentPhase: Phase
        get() = state.phase

    fun updateState(newState: CognitiveState) {
        state = newState
    }

    fun setPhase(phase: Phase, details: String = "") {
        state = state.copy(
            phase = phase,
            phaseStartTime = clock.now(),
            phaseDetails = details
        )
    }

    fun startDemo() {
        state = CognitiveState(
            phase = Phase.INITIALIZING,
            startTime = clock.now(),
            phaseStartTime = clock.now()
        )
    }

    fun setTicketInfo(ticketId: String, agentId: String) {
        state = state.copy(ticketId = ticketId, agentId = agentId)
    }

    /**
     * Set the coordinator agent for multi-agent demos.
     */
    fun setCoordinatorInfo(coordinatorId: String) {
        state = state.copy(coordinatorId = coordinatorId)
    }

    /**
     * Set the worker agent for multi-agent demos.
     */
    fun setWorkerInfo(workerId: String) {
        state = state.copy(workerId = workerId)
    }

    /**
     * Set the currently active agent in multi-agent execution.
     */
    fun setActiveAgent(agentId: String) {
        state = state.copy(activeAgentId = agentId)
    }

    fun setPerceiveResult(ideas: List<Idea>) {
        state = state.copy(
            ideasGenerated = ideas.size,
            ideaNames = ideas.map { it.name }
        )
    }

    fun setPlanResult(plan: Plan) {
        state = state.copy(
            planSteps = plan.tasks.size,
            estimatedComplexity = plan.estimatedComplexity,
            planApproach = summarizePlan(plan)
        )
    }

    private fun summarizePlan(plan: Plan): String {
        return plan.tasks.take(3).joinToString(" → ") { task ->
            when (task) {
                is Task.CodeChange -> "write code"
                is Task.Blank -> "initialize"
                else -> "task"
            }
        }
    }

    fun addFileWritten(filePath: String, content: String) {
        val fileInfo = FileWriteInfo(
            path = filePath,
            lineCount = content.lines().size,
            operationType = "created"
        )
        state = state.copy(filesWritten = state.filesWritten + fileInfo)
    }

    fun addKnowledgeStored(knowledge: String) {
        state = state.copy(
            knowledgeStored = state.knowledgeStored + knowledge.take(60)
        )
    }

    fun setFailed(error: String) {
        state = state.copy(phase = Phase.FAILED, errorMessage = error)
    }

    /**
     * Set the awaiting human state with escalation details.
     * This pauses the current phase (typically PLAN) to wait for human input.
     *
     * @param question The question to display to the human
     * @param options List of options for the human to choose from (e.g., [("A", "keep minimal"), ("B", "add both")])
     */
    fun setAwaitingHuman(question: String, options: List<Pair<String, String>>) {
        state = state.copy(
            escalation = EscalationInfo(
                question = question,
                options = options.map { EscalationOption(it.first, it.second) },
                startTime = clock.now()
            )
        )
    }

    /**
     * Clear the awaiting human state after human has responded.
     */
    fun clearAwaitingHuman() {
        state = state.copy(escalation = null)
    }

    /**
     * Update the auto-respond countdown timer display.
     * @param secondsRemaining Seconds until auto-respond, or null to clear countdown
     */
    fun setAutoRespondCountdown(secondsRemaining: Int?) {
        val currentEscalation = state.escalation ?: return
        state = state.copy(
            escalation = currentEscalation.copy(autoRespondSecondsRemaining = secondsRemaining)
        )
    }

    /**
     * Check if we're currently awaiting human input.
     */
    val isAwaitingHuman: Boolean
        get() = state.isAwaitingHuman

    /**
     * Get the current escalation options, if any.
     * Returns key-label pairs suitable for status bar display.
     */
    val escalationOptions: List<Pair<String, String>>
        get() = state.escalation?.options?.map { it.key to it.label } ?: emptyList()

    override fun render(width: Int, height: Int): List<String> {
        frameCounter++
        val lines = mutableListOf<String>()

        // Header with separator and spacing
        lines.addAll(renderSectionHeader("Cognitive Cycle", width, terminal))

        // If no goal started, show idle state
        if (state.startTime == null) {
            lines.add("")
            lines.add(terminal.render(dim("No active goal")))
            lines.add("")
            lines.add(terminal.render(dim("The cognitive cycle will")))
            lines.add(terminal.render(dim("show progress here once")))
            lines.add(terminal.render(dim("an agent starts working.")))
            lines.add("")
            lines.add(terminal.render(dim("─── PROPEL Loop ───────")))
            lines.add(terminal.render(dim("○ PERCEIVE - Analyze")))
            lines.add(terminal.render(dim("○ RECALL   - Remember")))
            lines.add(terminal.render(dim("○ OPTIMIZE - Prioritize")))
            lines.add(terminal.render(dim("○ PLAN     - Decide")))
            lines.add(terminal.render(dim("○ EXECUTE  - Act")))
            lines.add(terminal.render(dim("○ LOOP     - Learn")))

            // Pad to height
            while (lines.size < height - 2) {
                lines.add("")
            }

            // Footer
            lines.add("─".repeat(width))
            lines.add(terminal.render(dim("AMPERE Cognitive Cycle")))

            return lines.take(height).map { it.fitToWidth(width) }
        }

        // Active goal - show progress
        val elapsed = formatElapsed(state.startTime!!)
        lines.add("${terminal.render(dim("Elapsed:"))} $elapsed")
        lines.add("")

        // Ticket info - ALWAYS 3 lines (show placeholder if not set)
        val ticketDisplay = state.ticketId?.let { IdFormatter.formatTicketId(it) } ?: "..."
        lines.add("${terminal.render(dim("Ticket:"))} $ticketDisplay")

        // Agent info - show coordinator/worker labels if multi-agent
        if (state.isMultiAgent) {
            val coordinatorDisplay = state.coordinatorId?.let { IdFormatter.formatAgentId(it) } ?: "..."
            val workerDisplay = state.workerId?.let { IdFormatter.formatAgentId(it) } ?: "..."
            val coordinatorActive = state.activeAgentId == state.coordinatorId
            val workerActive = state.activeAgentId == state.workerId

            val coordinatorLabel = if (coordinatorActive) {
                terminal.render(TextColors.cyan("▸ Coordinator:"))
            } else {
                terminal.render(dim("  Coordinator:"))
            }
            val workerLabel = if (workerActive) {
                terminal.render(TextColors.cyan("▸ Worker:"))
            } else {
                terminal.render(dim("  Worker:"))
            }

            lines.add("$coordinatorLabel $coordinatorDisplay")
            lines.add("$workerLabel $workerDisplay")
        } else {
            val agentDisplay = state.agentId?.let { IdFormatter.formatAgentId(it) } ?: "..."
            lines.add("${terminal.render(dim("Agent:"))} $agentDisplay")
            lines.add("")
        }

        // Cognitive cycle sub-header
        lines.addAll(renderSubHeader("Cognitive Cycle", width, terminal))

        // Cognitive cycle tree view
        lines.addAll(renderPhaseTree(width))

        // Status section - ALWAYS 3 lines
        lines.add("")
        val statusLine = when (state.phase) {
            Phase.COMPLETED -> terminal.render(TextColors.green("[COMPLETED]"))
            Phase.FAILED -> terminal.render(TextColors.red("[FAILED]"))
            else -> ""
        }
        lines.add(statusLine)
        val errorLine = if (state.phase == Phase.FAILED) {
            state.errorMessage?.let { terminal.render(dim(it.take(width - 4))) } ?: ""
        } else {
            ""
        }
        lines.add(errorLine)

        // Pad to height
        while (lines.size < height - 2) {
            lines.add("")
        }

        // Footer
        lines.add("─".repeat(width))
        lines.add(terminal.render(dim("AMPERE Cognitive Cycle")))

        return lines.take(height).map { it.fitToWidth(width) }
    }

    /**
     * Render the cognitive cycle phases as a tree view.
     *
     * Format:
     * ├─ PERCEIVE  ✓ Complete
     * │    └─ Ideas: 3 - task-create command implementation
     * ├─ PLAN      ◐ Creating plan... (5s)
     * │    └─ Steps: 4  Complexity: medium
     * ├─ EXECUTE   ○ Writing code
     * │    └─ (pending)
     * └─ LEARN     ○ Extracting knowledge
     *      └─ (pending)
     */
    private fun renderPhaseTree(width: Int): List<String> {
        val lines = mutableListOf<String>()
        val phases = listOf(
            Triple(Phase.PERCEIVE, "PERCEIVE", "Analyzing state"),
            Triple(Phase.PLAN, "PLAN", "Creating plan"),
            Triple(Phase.EXECUTE, "EXECUTE", "Writing code"),
            Triple(Phase.LEARN, "LEARN", "Extracting knowledge")
        )

        phases.forEachIndexed { index, (phase, name, description) ->
            val isLast = index == phases.lastIndex
            val treeChar = if (isLast) "└─" else "├─"
            val continueChar = if (isLast) "   " else "│  "

            // Check if this phase is awaiting human input
            val isAwaitingHumanInPhase = state.phase == phase && state.isAwaitingHuman

            // Phase indicator
            val indicator = when {
                isAwaitingHumanInPhase -> terminal.render(TextColors.magenta("⏳"))
                state.phase == phase -> getAnimatedIndicator()
                state.phase.ordinal > phase.ordinal -> terminal.render(TextColors.green("✓"))
                else -> terminal.render(dim("○"))
            }

            // Phase name color
            val nameColor = when {
                isAwaitingHumanInPhase -> TextColors.magenta
                state.phase == phase -> TextColors.yellow
                state.phase.ordinal > phase.ordinal -> TextColors.green
                else -> TextColors.gray
            }

            // Phase status text
            val statusText = when {
                isAwaitingHumanInPhase -> {
                    val waitingElapsed = state.escalation?.startTime?.let { formatElapsed(it) } ?: ""
                    "Awaiting human input ($waitingElapsed)"
                }
                state.phase == phase -> {
                    val phaseElapsed = state.phaseStartTime?.let { formatElapsed(it) } ?: ""
                    if (state.phaseDetails.isNotEmpty()) {
                        "${state.phaseDetails} ($phaseElapsed)"
                    } else {
                        "$description... ($phaseElapsed)"
                    }
                }
                state.phase.ordinal > phase.ordinal -> "Complete"
                else -> description
            }

            // Main phase line
            val phaseLabel = terminal.render(nameColor(name.padEnd(10)))
            lines.add("$treeChar $indicator $phaseLabel ${terminal.render(dim(statusText))}")

            // Details for completed or active phases
            val details = getPhaseDetails(phase, width - 6)
            details.forEachIndexed { detailIdx, detail ->
                val detailTreeChar = if (detailIdx == details.lastIndex) "└─" else "├─"
                lines.add("$continueChar  $detailTreeChar ${terminal.render(dim(detail))}")
            }

            // Add spacing between phases (except the last)
            if (!isLast) {
                lines.add(terminal.render(dim(continueChar)))
            }
        }

        return lines
    }

    /**
     * Get detail lines for a phase based on current state.
     */
    private fun getPhaseDetails(phase: Phase, maxWidth: Int): List<String> {
        return when (phase) {
            Phase.PERCEIVE -> {
                if (state.phase.ordinal > Phase.PERCEIVE.ordinal && state.ideasGenerated > 0) {
                    val ideaName = state.ideaNames.firstOrNull()?.take(maxWidth - 15) ?: ""
                    listOf("Ideas: ${state.ideasGenerated}${if (ideaName.isNotEmpty()) " - $ideaName" else ""}")
                } else if (state.phase == Phase.PERCEIVE) {
                    listOf("analyzing...")
                } else {
                    emptyList()
                }
            }
            Phase.PLAN -> {
                if (state.phase == Phase.PLAN && state.isAwaitingHuman) {
                    // Show escalation question and options when awaiting human input
                    val escalation = state.escalation!!
                    val details = mutableListOf(escalation.question.take(maxWidth))
                    if (escalation.options.isNotEmpty()) {
                        val optionsStr = escalation.options.joinToString("  ") { option ->
                            "[${option.key}] ${option.label}"
                        }
                        details.add("Press $optionsStr".take(maxWidth))
                    }
                    // Show auto-respond countdown if active
                    escalation.autoRespondSecondsRemaining?.let { seconds ->
                        details.add("Auto-responding with [A] in ${seconds}s...")
                    }
                    details
                } else if (state.phase.ordinal > Phase.PLAN.ordinal && state.planSteps > 0) {
                    val details = mutableListOf("Steps: ${state.planSteps}  Complexity: ${state.estimatedComplexity}")
                    if (state.planApproach.isNotEmpty()) {
                        details.add(state.planApproach.take(maxWidth))
                    }
                    details
                } else if (state.phase == Phase.PLAN) {
                    listOf("creating plan...")
                } else {
                    emptyList()
                }
            }
            Phase.EXECUTE -> {
                if (state.filesWritten.isNotEmpty()) {
                    state.filesWritten.take(3).map { fileInfo ->
                        val fileName = fileInfo.path.substringAfterLast('/')
                        "→ $fileName (${fileInfo.lineCount} lines)"
                    }
                } else if (state.phase == Phase.EXECUTE) {
                    listOf("writing code...")
                } else {
                    emptyList()
                }
            }
            Phase.LEARN -> {
                if (state.phase.ordinal > Phase.LEARN.ordinal && state.knowledgeStored.isNotEmpty()) {
                    listOf("Stored: \"${state.knowledgeStored.firstOrNull()?.take(maxWidth - 10) ?: ""}\"")
                } else if (state.phase == Phase.LEARN) {
                    listOf("extracting knowledge...")
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun renderPhaseRow(phase: Phase, name: String, description: String, width: Int): List<String> {
        val lines = mutableListOf<String>()

        val indicator = when {
            state.phase == phase -> getAnimatedIndicator()
            state.phase.ordinal > phase.ordinal -> terminal.render(TextColors.green("✓"))
            else -> terminal.render(dim("○"))
        }

        val nameColor = when {
            state.phase == phase -> TextColors.yellow
            state.phase.ordinal > phase.ordinal -> TextColors.green
            else -> TextColors.gray
        }

        val statusText = when {
            state.phase == phase -> {
                val phaseElapsed = state.phaseStartTime?.let { formatElapsed(it) } ?: ""
                if (state.phaseDetails.isNotEmpty()) {
                    "${state.phaseDetails} ($phaseElapsed)"
                } else {
                    "$description... ($phaseElapsed)"
                }
            }
            state.phase.ordinal > phase.ordinal -> "Complete"
            else -> description
        }

        val phaseLabel = terminal.render(nameColor(name.padEnd(10)))
        lines.add("$indicator $phaseLabel ${terminal.render(dim(statusText))}")

        return lines
    }

    private fun getAnimatedIndicator(): String {
        val frame = dischargeSequence[(frameCounter % dischargeSequence.size).toInt()]
        return "${frame.glow.toAnsi()}${frame.symbol}\u001B[0m"
    }

    private fun formatElapsed(since: Instant): String {
        val elapsed = clock.now().toEpochMilliseconds() - since.toEpochMilliseconds()
        val seconds = elapsed / 1000
        val minutes = seconds / 60
        return when {
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
