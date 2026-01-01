package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.cli.animation.LightningAnimator

/**
 * Renders the Jazz Test execution progress as a pane.
 *
 * Displays the cognitive cycle phases (PERCEIVE, PLAN, EXECUTE, LEARN)
 * with progress indicators and real-time status updates.
 */
class JazzProgressPane(
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
     * Current state of the Jazz demo execution.
     */
    data class JazzState(
        val phase: Phase = Phase.INITIALIZING,
        val startTime: Instant? = null,
        val phaseStartTime: Instant? = null,
        val ticketId: String? = null,
        val agentId: String? = null,
        val ideasGenerated: Int = 0,
        val planSteps: Int = 0,
        val estimatedComplexity: Int = 0,
        val filesWritten: List<String> = emptyList(),
        val errorMessage: String? = null,
        val phaseDetails: String = ""
    )

    private var state = JazzState()

    /**
     * Get the current phase for external observation.
     */
    val currentPhase: Phase
        get() = state.phase

    fun updateState(newState: JazzState) {
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
        state = JazzState(
            phase = Phase.INITIALIZING,
            startTime = clock.now(),
            phaseStartTime = clock.now()
        )
    }

    fun setTicketInfo(ticketId: String, agentId: String) {
        state = state.copy(ticketId = ticketId, agentId = agentId)
    }

    fun setPerceiveResult(ideasGenerated: Int) {
        state = state.copy(ideasGenerated = ideasGenerated)
    }

    fun setPlanResult(planSteps: Int, complexity: Int) {
        state = state.copy(planSteps = planSteps, estimatedComplexity = complexity)
    }

    fun addFileWritten(filePath: String) {
        state = state.copy(filesWritten = state.filesWritten + filePath)
    }

    fun setFailed(error: String) {
        state = state.copy(phase = Phase.FAILED, errorMessage = error)
    }

    override fun render(width: Int, height: Int): List<String> {
        frameCounter++
        val lines = mutableListOf<String>()

        // Header with title (3 lines)
        lines.add(renderHeader(width))
        lines.add("─".repeat(width))
        lines.add("")

        // Elapsed time (2 lines)
        val elapsed = state.startTime?.let { formatElapsed(it) } ?: "0s"
        lines.add("${terminal.render(dim("Elapsed:"))} $elapsed")
        lines.add("")

        // Ticket info - ALWAYS 3 lines (show placeholder if not set)
        lines.add("${terminal.render(dim("Ticket:"))} ${state.ticketId?.take(20) ?: "..."}")
        lines.add("${terminal.render(dim("Agent:"))} ${state.agentId?.takeLast(15) ?: "..."}")
        lines.add("")

        // Cognitive cycle header (2 lines)
        lines.add(terminal.render(bold("Cognitive Cycle")))
        lines.add("")

        // PERCEIVE phase - ALWAYS 3 lines
        lines.addAll(renderPhaseRow(Phase.PERCEIVE, "PERCEIVE", "Analyzing state", width))
        val perceiveDetail = if (state.phase.ordinal > Phase.PERCEIVE.ordinal && state.ideasGenerated > 0) {
            "   ${terminal.render(dim("Ideas generated: ${state.ideasGenerated}"))}"
        } else {
            ""
        }
        lines.add(perceiveDetail)
        lines.add("")

        // PLAN phase - ALWAYS 3 lines
        lines.addAll(renderPhaseRow(Phase.PLAN, "PLAN", "Creating plan", width))
        val planDetail = if (state.phase.ordinal > Phase.PLAN.ordinal && state.planSteps > 0) {
            "   ${terminal.render(dim("Steps: ${state.planSteps}  Complexity: ${state.estimatedComplexity}"))}"
        } else {
            ""
        }
        lines.add(planDetail)
        lines.add("")

        // EXECUTE phase - ALWAYS 5 lines (phase + up to 3 files + blank)
        lines.addAll(renderPhaseRow(Phase.EXECUTE, "EXECUTE", "Writing code", width))
        val files = state.filesWritten.take(3)
        for (i in 0 until 3) {
            val fileLine = files.getOrNull(i)?.let { "   ${terminal.render(dim("→ $it"))}" } ?: ""
            lines.add(fileLine)
        }
        lines.add("")

        // LEARN phase - ALWAYS 2 lines
        lines.addAll(renderPhaseRow(Phase.LEARN, "LEARN", "Extracting knowledge", width))
        lines.add("")

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
        lines.add(terminal.render(dim("Jazz Test Demo")))

        return lines.take(height).map { it.fitToWidth(width) }
    }

    private fun renderHeader(width: Int): String {
        val title = terminal.render(bold(TextColors.yellow("THE JAZZ TEST")))
        return title
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
