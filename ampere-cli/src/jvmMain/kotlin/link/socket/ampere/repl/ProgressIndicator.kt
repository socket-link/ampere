package link.socket.ampere.repl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jline.terminal.Terminal
import java.io.PrintWriter

/**
 * ANSI escape sequences for terminal control.
 */
private object TerminalCodes {
    const val HIDE_CURSOR = "\u001b[?25l"
    const val SHOW_CURSOR = "\u001b[?25h"
    const val CLEAR_LINE = "\u001b[2K"
    const val MOVE_TO_START = "\u001b[0G"
    const val RESET = "\u001b[0m"
    const val BOLD = "\u001b[1m"
    const val GREEN = "\u001b[32m"
    const val RED = "\u001b[31m"
    const val YELLOW = "\u001b[33m"
    const val CYAN = "\u001b[36m"
}

/**
 * Reusable progress indicator component for CLI applications.
 *
 * Supports three operational modes:
 * - **Spinner mode**: Rotating symbols for indeterminate progress
 * - **Progress bar mode**: Determinate (with percentage) and indeterminate states
 * - **State indicator mode**: Success, error, or waiting conditions
 *
 * Technical features:
 * - Frame rate capped at 50ms intervals (20fps maximum)
 * - Cursor visibility control during animations
 * - Efficient partial updates using terminal control sequences
 * - Buffered output with explicit flush operations
 */
interface ProgressIndicator {
    /**
     * Start the progress indicator with an optional message.
     */
    fun start()

    /**
     * Update the progress indicator.
     * @param progress Optional progress value (0.0 to 1.0) for determinate mode
     * @param message Optional message to display
     */
    fun update(progress: Float? = null, message: String? = null)

    /**
     * Complete the progress indicator.
     * @param success Whether the operation was successful
     */
    fun complete(success: Boolean = true)

    /**
     * Stop and clear the progress indicator.
     */
    fun stop()
}

/**
 * Progress indicator mode.
 */
enum class IndicatorMode {
    /** Rotating spinner symbols */
    SPINNER,
    /** Progress bar with optional percentage */
    PROGRESS_BAR,
    /** State indicator (success/error/waiting) */
    STATE
}

/**
 * State for state indicator mode.
 */
enum class IndicatorState {
    WAITING,
    SUCCESS,
    ERROR
}

/**
 * Builder for creating progress indicators with various configurations.
 *
 * Usage:
 * ```kotlin
 * val spinner = ProgressIndicatorBuilder(terminal)
 *     .mode(IndicatorMode.SPINNER)
 *     .message("Loading...")
 *     .build()
 * spinner.start()
 * // ... do work ...
 * spinner.complete()
 * ```
 */
class ProgressIndicatorBuilder(private val terminal: Terminal) {
    private var mode: IndicatorMode = IndicatorMode.SPINNER
    private var message: String = ""
    private var useColors: Boolean = true
    private var useUnicode: Boolean = true
    private var isInteractive: Boolean = true

    fun mode(mode: IndicatorMode) = apply { this.mode = mode }
    fun message(message: String) = apply { this.message = message }
    fun useColors(enabled: Boolean) = apply { this.useColors = enabled }
    fun useUnicode(enabled: Boolean) = apply { this.useUnicode = enabled }
    fun isInteractive(enabled: Boolean) = apply { this.isInteractive = enabled }

    /**
     * Configure from detected terminal capabilities.
     */
    fun withCapabilities(capabilities: TerminalFactory.TerminalCapabilities) = apply {
        this.useColors = capabilities.supportsColors
        this.useUnicode = capabilities.supportsUnicode
        this.isInteractive = capabilities.isInteractive
    }

    fun build(): ProgressIndicator = when (mode) {
        IndicatorMode.SPINNER -> SpinnerIndicator(terminal, message, useColors, useUnicode, isInteractive)
        IndicatorMode.PROGRESS_BAR -> ProgressBarIndicator(terminal, message, useColors, useUnicode, isInteractive)
        IndicatorMode.STATE -> StateIndicator(terminal, message, useColors, useUnicode, isInteractive)
    }
}

/**
 * Base class for progress indicators with common functionality.
 */
abstract class BaseProgressIndicator(
    protected val terminal: Terminal,
    protected var message: String,
    protected val useColors: Boolean,
    protected val useUnicode: Boolean,
    protected val isInteractive: Boolean
) : ProgressIndicator {

    protected var job: Job? = null
    protected val writer: PrintWriter get() = terminal.writer()

    companion object {
        /** Frame interval in milliseconds (20fps max) */
        const val FRAME_INTERVAL_MS = 50L
    }

    protected fun hideCursor() {
        if (!isInteractive) return
        writer.print(TerminalCodes.HIDE_CURSOR)
        writer.flush()
    }

    protected fun showCursor() {
        if (!isInteractive) return
        writer.print(TerminalCodes.SHOW_CURSOR)
        writer.flush()
    }

    protected fun clearLine() {
        if (!isInteractive) return
        writer.print("${TerminalCodes.MOVE_TO_START}${TerminalCodes.CLEAR_LINE}")
    }

    protected fun colored(text: String, color: String): String {
        return if (useColors) "$color$text${TerminalCodes.RESET}" else text
    }

    protected fun flush() {
        writer.flush()
    }

    override fun stop() {
        job?.cancel()
        job = null
        clearLine()
        showCursor()
        flush()
    }
}

/**
 * Spinner progress indicator with rotating symbols.
 *
 * Shows a spinning animation to indicate indeterminate progress.
 */
class SpinnerIndicator(
    terminal: Terminal,
    message: String,
    useColors: Boolean,
    useUnicode: Boolean,
    isInteractive: Boolean
) : BaseProgressIndicator(terminal, message, useColors, useUnicode, isInteractive) {

    private val frames: List<String>
        get() = if (useUnicode) TerminalSymbols.Spinner.unicodeFrames else TerminalSymbols.Spinner.asciiFrames

    override fun start() {
        if (!isInteractive) {
            writer.println("${TerminalSymbols.Spinner.staticIndicator} $message")
            flush()
            return
        }

        hideCursor()
        job = CoroutineScope(Dispatchers.Default).launch {
            var frameIndex = 0
            while (isActive) {
                renderFrame(frames[frameIndex])
                frameIndex = (frameIndex + 1) % frames.size
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun renderFrame(frame: String) {
        clearLine()
        val coloredFrame = colored(frame, TerminalCodes.CYAN)
        writer.print("$coloredFrame $message")
        flush()
    }

    override fun update(progress: Float?, message: String?) {
        message?.let { this.message = it }
    }

    override fun complete(success: Boolean) {
        job?.cancel()
        job = null
        clearLine()
        val symbol = if (useUnicode) {
            if (success) "✓" else "✗"
        } else {
            if (success) "[OK]" else "[X]"
        }
        val color = if (success) TerminalCodes.GREEN else TerminalCodes.RED
        writer.println("${colored(symbol, color)} $message")
        showCursor()
        flush()
    }
}

/**
 * Progress bar indicator with determinate and indeterminate modes.
 *
 * Determinate mode: Shows a progress bar with percentage (when progress is provided)
 * Indeterminate mode: Shows an animated progress bar (when progress is null)
 */
class ProgressBarIndicator(
    terminal: Terminal,
    message: String,
    useColors: Boolean,
    useUnicode: Boolean,
    isInteractive: Boolean
) : BaseProgressIndicator(terminal, message, useColors, useUnicode, isInteractive) {

    private var currentProgress: Float? = null
    private var indeterminatePosition = 0
    private var lastBarWidth = DEFAULT_BAR_WIDTH

    companion object {
        private const val DEFAULT_BAR_WIDTH = 20
        private const val MAX_BAR_WIDTH = 40
        private const val PERCENT_WIDTH = 4
    }

    // Progress bar characters
    private val filledChar: String get() = if (useUnicode) "█" else "#"
    private val emptyChar: String get() = if (useUnicode) "░" else "-"
    private val bounceChar: String get() = if (useUnicode) "▓" else "="

    override fun start() {
        if (!isInteractive) {
            writer.println("${TerminalSymbols.Spinner.staticIndicator} $message")
            flush()
            return
        }

        hideCursor()
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val barWidth = computeBarWidth()
                renderBar(barWidth)
                if (currentProgress == null) {
                    // Indeterminate mode: animate
                    val travel = ((barWidth - 1) * 2).coerceAtLeast(1)
                    indeterminatePosition = (indeterminatePosition + 1) % travel
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun renderBar(barWidth: Int) {
        clearLine()
        lastBarWidth = barWidth
        val bar = if (currentProgress != null) {
            renderDeterminateBar(currentProgress!!, barWidth)
        } else {
            renderIndeterminateBar(barWidth)
        }
        writer.print("$bar $message")
        flush()
    }

    private fun renderDeterminateBar(progress: Float, barWidth: Int): String {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val filled = (clampedProgress * barWidth).toInt()
        val empty = (barWidth - filled).coerceAtLeast(0)
        val percentage = (clampedProgress * 100).toInt()

        val filledPart = colored(filledChar.repeat(filled), TerminalCodes.CYAN)
        val emptyPart = emptyChar.repeat(empty)
        return "[$filledPart$emptyPart] $percentage%"
    }

    private fun renderIndeterminateBar(barWidth: Int): String {
        val travel = ((barWidth - 1) * 2).coerceAtLeast(1)
        val normalizedPosition = indeterminatePosition % travel
        val pos = if (barWidth <= 1) {
            0
        } else if (normalizedPosition < barWidth) {
            normalizedPosition
        } else {
            ((barWidth - 1) * 2) - normalizedPosition
        }

        val bar = buildString {
            for (i in 0 until barWidth) {
                if (i == pos) {
                    append(colored(bounceChar, TerminalCodes.CYAN))
                } else {
                    append(emptyChar)
                }
            }
        }
        return "[$bar]"
    }

    private fun computeBarWidth(): Int {
        val terminalWidth = terminal.width
        if (terminalWidth <= 0) {
            return DEFAULT_BAR_WIDTH
        }

        val reservedForMessage = 1 + message.length
        val reservedForPercent = if (currentProgress == null) 0 else 1 + PERCENT_WIDTH
        val reserved = 2 + reservedForPercent + reservedForMessage
        val available = terminalWidth - reserved
        return available.coerceAtLeast(1).coerceAtMost(MAX_BAR_WIDTH)
    }

    override fun update(progress: Float?, message: String?) {
        progress?.let { this.currentProgress = it }
        message?.let { this.message = it }
    }

    override fun complete(success: Boolean) {
        job?.cancel()
        job = null
        clearLine()

        val symbol = if (useUnicode) {
            if (success) "✓" else "✗"
        } else {
            if (success) "[OK]" else "[X]"
        }
        val color = if (success) TerminalCodes.GREEN else TerminalCodes.RED

        // Show completed bar
        val completedBar = if (success) {
            colored(filledChar.repeat(lastBarWidth), TerminalCodes.GREEN)
        } else {
            colored(filledChar.repeat(lastBarWidth), TerminalCodes.RED)
        }
        writer.println("[$completedBar] ${colored(symbol, color)} $message")
        showCursor()
        flush()
    }
}

/**
 * State indicator for showing success, error, or waiting states.
 *
 * Unlike spinner and progress bar, this doesn't animate by default
 * but shows a static indicator that can be updated.
 */
class StateIndicator(
    terminal: Terminal,
    message: String,
    useColors: Boolean,
    useUnicode: Boolean,
    isInteractive: Boolean
) : BaseProgressIndicator(terminal, message, useColors, useUnicode, isInteractive) {

    private var state: IndicatorState = IndicatorState.WAITING
    private var animateWaiting: Boolean = true

    // State symbols
    private val waitingSymbolUnicode = listOf("◐", "◓", "◑", "◒")
    private val waitingSymbolAscii = listOf("(o)", "(O)", "(o)", "(O)")
    private val successSymbol: String get() = if (useUnicode) "✓" else "[OK]"
    private val errorSymbol: String get() = if (useUnicode) "✗" else "[X]"

    private val waitingSymbols: List<String>
        get() = if (useUnicode) waitingSymbolUnicode else waitingSymbolAscii

    override fun start() {
        if (!isInteractive) {
            writer.println("${TerminalSymbols.Spinner.staticIndicator} $message")
            flush()
            return
        }

        hideCursor()
        state = IndicatorState.WAITING
        job = CoroutineScope(Dispatchers.Default).launch {
            var frameIndex = 0
            while (isActive) {
                renderState(frameIndex)
                if (state == IndicatorState.WAITING && animateWaiting) {
                    frameIndex = (frameIndex + 1) % waitingSymbols.size
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun renderState(frameIndex: Int) {
        clearLine()
        val (symbol, color) = when (state) {
            IndicatorState.WAITING -> waitingSymbols[frameIndex] to TerminalCodes.YELLOW
            IndicatorState.SUCCESS -> successSymbol to TerminalCodes.GREEN
            IndicatorState.ERROR -> errorSymbol to TerminalCodes.RED
        }
        writer.print("${colored(symbol, color)} $message")
        flush()
    }

    /**
     * Set the current state of the indicator.
     */
    fun setState(newState: IndicatorState) {
        this.state = newState
    }

    override fun update(progress: Float?, message: String?) {
        message?.let { this.message = it }
        // progress is ignored for state indicator
    }

    override fun complete(success: Boolean) {
        state = if (success) IndicatorState.SUCCESS else IndicatorState.ERROR
        job?.cancel()
        job = null
        clearLine()
        val symbol = if (success) successSymbol else errorSymbol
        val color = if (success) TerminalCodes.GREEN else TerminalCodes.RED
        writer.println("${colored(symbol, color)} $message")
        showCursor()
        flush()
    }
}

/**
 * Extension function for backward compatibility with existing code.
 * Creates a simple spinner indicator.
 */
fun Terminal.createSpinner(message: String = ""): ProgressIndicator {
    val capabilities = TerminalFactory.getCapabilities()
    return ProgressIndicatorBuilder(this)
        .mode(IndicatorMode.SPINNER)
        .message(message)
        .withCapabilities(capabilities)
        .build()
}

/**
 * Extension function to create a progress bar indicator.
 */
fun Terminal.createProgressBar(message: String = ""): ProgressIndicator {
    val capabilities = TerminalFactory.getCapabilities()
    return ProgressIndicatorBuilder(this)
        .mode(IndicatorMode.PROGRESS_BAR)
        .message(message)
        .withCapabilities(capabilities)
        .build()
}

/**
 * Extension function to create a state indicator.
 */
fun Terminal.createStateIndicator(message: String = ""): StateIndicator {
    val capabilities = TerminalFactory.getCapabilities()
    return ProgressIndicatorBuilder(this)
        .mode(IndicatorMode.STATE)
        .message(message)
        .withCapabilities(capabilities)
        .build() as StateIndicator
}

/**
 * Simple spinner for backward compatibility with existing code.
 *
 * This class provides the original ProgressIndicator API:
 * ```kotlin
 * val indicator = SimpleSpinner(terminal)
 * indicator.start("Loading...")
 * // ... work ...
 * indicator.stop()
 * ```
 */
class SimpleSpinner(private val terminal: Terminal) {
    private var indicator: ProgressIndicator? = null

    /**
     * Start showing the spinner with a message.
     *
     * In non-interactive mode (piped output), displays a static message
     * without animation to avoid cluttering output with cursor control codes.
     */
    fun start(message: String) {
        val capabilities = TerminalFactory.getCapabilities()
        indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.SPINNER)
            .message(message)
            .withCapabilities(capabilities)
            .build()
        indicator?.start()
    }

    /**
     * Stop the spinner.
     */
    fun stop() {
        indicator?.stop()
        indicator = null
    }
}
