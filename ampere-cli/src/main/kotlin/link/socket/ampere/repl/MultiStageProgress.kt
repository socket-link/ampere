package link.socket.ampere.repl

import org.jline.terminal.Terminal
import java.io.PrintWriter

/**
 * Multi-stage progress display for complex operations.
 *
 * Renders a step counter with an in-progress symbol and completion symbol.
 * Example: [1/3] ⚡ Initializing agents...
 */
class MultiStageProgress(
    private val terminal: Terminal,
    private val totalSteps: Int,
    private val useUnicode: Boolean
) {
    private var currentStep = 0
    private val writer: PrintWriter get() = terminal.writer()

    init {
        require(totalSteps > 0) { "totalSteps must be greater than 0" }
    }

    /**
     * Render a specific step in progress.
     */
    fun step(step: Int, message: String, remainingSeconds: Long? = null) {
        val normalizedStep = normalizeStep(step)
        currentStep = normalizedStep
        render(normalizedStep, message, StageStatus.IN_PROGRESS, remainingSeconds)
    }

    /**
     * Render the next step in progress, incrementing the internal counter.
     */
    fun step(message: String, remainingSeconds: Long? = null): Int {
        val nextStep = (currentStep + 1).coerceAtMost(totalSteps)
        currentStep = nextStep
        render(nextStep, message, StageStatus.IN_PROGRESS, remainingSeconds)
        return nextStep
    }

    /**
     * Render a specific step as completed.
     */
    fun complete(step: Int, message: String) {
        val normalizedStep = normalizeStep(step)
        currentStep = normalizedStep
        render(normalizedStep, message, StageStatus.COMPLETED, null)
    }

    /**
     * Render the current step as completed.
     */
    fun complete(message: String): Int {
        if (currentStep == 0) {
            currentStep = 1
        }
        render(currentStep, message, StageStatus.COMPLETED, null)
        return currentStep
    }

    private fun render(step: Int, message: String, status: StageStatus, remainingSeconds: Long?) {
        val symbol = when (status) {
            StageStatus.IN_PROGRESS -> lightningSymbol
            StageStatus.COMPLETED -> checkSymbol
        }

        val remaining = remainingSeconds?.let { " (${formatRemaining(it)})" }.orEmpty()
        writer.println("[${step}/${totalSteps}] $symbol $message$remaining")
        writer.flush()
    }

    private fun normalizeStep(step: Int): Int {
        return step.coerceIn(1, totalSteps)
    }

    private fun formatRemaining(remainingSeconds: Long): String {
        val clamped = remainingSeconds.coerceAtLeast(0)
        val minutes = clamped / 60
        val seconds = clamped % 60
        val formatted = if (minutes > 0) {
            if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes}m"
        } else {
            "${seconds}s"
        }
        return "~$formatted remaining"
    }

    private val lightningSymbol: String get() = if (useUnicode) "⚡" else "*"
    private val checkSymbol: String get() = if (useUnicode) "✓" else "[OK]"

    private enum class StageStatus {
        IN_PROGRESS,
        COMPLETED
    }
}

class MultiStageProgressBuilder(
    private val terminal: Terminal,
    private val totalSteps: Int
) {
    private var useUnicode: Boolean = true

    fun useUnicode(enabled: Boolean) = apply { this.useUnicode = enabled }

    fun withCapabilities(capabilities: TerminalFactory.TerminalCapabilities) = apply {
        this.useUnicode = capabilities.supportsUnicode
    }

    fun build(): MultiStageProgress = MultiStageProgress(terminal, totalSteps, useUnicode)
}

/**
 * Extension function to create a multi-stage progress display with detected capabilities.
 */
fun Terminal.createMultiStageProgress(totalSteps: Int): MultiStageProgress {
    val capabilities = TerminalFactory.getCapabilities()
    return MultiStageProgressBuilder(this, totalSteps)
        .withCapabilities(capabilities)
        .build()
}
