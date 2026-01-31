package link.socket.ampere.cli.animation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import link.socket.ampere.repl.TerminalSymbols
import kotlin.math.abs

/**
 * Lightning Animation Primitives for AMPERE CLI.
 *
 * Provides terminal-based lightning effects inspired by electrical discharge:
 * - Discharge cycle sequences (charge → strike → afterglow)
 * - Corona glow effect for text (brightness radiating from center)
 * - ANSI color management for consistent terminal rendering
 *
 * Adapts to terminal capabilities:
 * - Unicode terminals: Uses decorative symbols (ϟ, ✦, ✧, etc.)
 * - ASCII terminals: Uses simple characters (#, *, |, etc.)
 * - Non-interactive mode: Shows static indicator without animation
 *
 * This is a standalone animation library - integration with CLI components
 * will come in future tickets.
 */
object LightningAnimator {

    /**
     * ANSI escape codes for terminal control and color.
     */
    private object Ansi {
        const val RESET = "\u001b[0m"
        const val BOLD = "\u001b[1m"
        const val HIDE_CURSOR = "\u001b[?25l"
        const val SHOW_CURSOR = "\u001b[?25h"
        const val CLEAR_LINE = "\u001b[2K"
        const val MOVE_TO_START = "\u001b[0G"

        fun fg256(code: Int): String = "\u001b[38;5;${code}m"
    }

    /**
     * Glow intensity levels for lightning effects.
     * Maps to ANSI 256-color palette for electrical luminosity.
     */
    enum class Glow {
        DARK,    // Nearly invisible
        DIM,     // Faint
        NORMAL,  // Visible
        BRIGHT,  // Intense
        FLASH;   // Maximum luminosity

        fun toAnsi(): String = when (this) {
            DARK -> Ansi.fg256(236)
            DIM -> Ansi.fg256(240)
            NORMAL -> Ansi.fg256(250)
            BRIGHT -> Ansi.fg256(226)
            FLASH -> "${Ansi.fg256(231)}${Ansi.BOLD}"
        }
    }

    /**
     * A single frame in a discharge animation sequence.
     *
     * @param symbol The character to display
     * @param glow The brightness level
     * @param durationMs How long to display this frame
     */
    data class DischargeFrame(
        val symbol: String,
        val glow: Glow,
        val durationMs: Long = 100
    )

    /**
     * Full discharge sequence - models lightning formation:
     * ground state → charge → stepped leader → strike → afterglow → rest
     *
     * Uses Unicode symbols when available, falls back to ASCII otherwise.
     */
    val DISCHARGE_SEQUENCE: List<DischargeFrame>
        get() {
            val symbols = TerminalSymbols.Lightning.discharge
            return listOf(
                DischargeFrame(symbols[0], Glow.DIM, 150),
                DischargeFrame(symbols[1], Glow.DIM, 80),
                DischargeFrame(symbols[2], Glow.NORMAL, 80),
                DischargeFrame(symbols[3], Glow.NORMAL, 70),
                DischargeFrame(symbols[4], Glow.BRIGHT, 60),
                DischargeFrame(symbols[5], Glow.BRIGHT, 50),
                DischargeFrame(symbols[6], Glow.BRIGHT, 40),
                DischargeFrame(symbols[7], Glow.FLASH, 120),
                DischargeFrame(symbols[8], Glow.BRIGHT, 100),
                DischargeFrame(symbols[9], Glow.NORMAL, 100),
                DischargeFrame(symbols[10], Glow.DIM, 200)
            )
        }

    /**
     * Compact sequence for tighter loops.
     */
    val COMPACT_SEQUENCE: List<DischargeFrame>
        get() {
            val symbols = TerminalSymbols.Lightning.compact
            return listOf(
                DischargeFrame(symbols[0], Glow.DIM, 120),
                DischargeFrame(symbols[1], Glow.NORMAL, 80),
                DischargeFrame(symbols[2], Glow.BRIGHT, 60),
                DischargeFrame(symbols[3], Glow.FLASH, 100),
                DischargeFrame(symbols[4], Glow.NORMAL, 100),
                DischargeFrame(symbols[5], Glow.DIM, 150)
            )
        }

    /**
     * Standard spinner sequence for general async operations.
     */
    val STANDARD_SPINNER = listOf(
        DischargeFrame("◐", Glow.NORMAL, 150),
        DischargeFrame("◓", Glow.NORMAL, 150),
        DischargeFrame("◑", Glow.NORMAL, 150),
        DischargeFrame("◒", Glow.NORMAL, 150)
    )

    /**
     * Lightning pulse sequence for high-intensity operations.
     */
    val LIGHTNING_PULSE = listOf(
        DischargeFrame("·", Glow.DIM, 120),
        DischargeFrame("˙", Glow.DIM, 80),
        DischargeFrame("⁝", Glow.NORMAL, 70),
        DischargeFrame("⚡", Glow.FLASH, 100),
        DischargeFrame("✧", Glow.NORMAL, 80),
        DischargeFrame("·", Glow.DIM, 150)
    )

    /**
     * Generates glow levels for each character position.
     * Brightness falls off with distance from center.
     *
     * @param textLength Total number of characters
     * @param centerPos Position of the glow center (0-based)
     * @return List of glow levels, one per character
     */
    fun coronaPattern(textLength: Int, centerPos: Int): List<Glow> {
        return (0 until textLength).map { pos ->
            val distance = abs(pos - centerPos)
            when {
                distance == 0 -> Glow.FLASH
                distance == 1 -> Glow.BRIGHT
                distance == 2 -> Glow.NORMAL
                distance <= 4 -> Glow.DIM
                else -> Glow.DARK
            }
        }
    }

    /**
     * Applies corona glow to text, returning ANSI-colored string.
     *
     * @param text The text to apply glow to
     * @param centerPos Position of the glow center
     * @return ANSI-formatted string with glow effect
     */
    fun applyCorona(text: String, centerPos: Int): String = buildString {
        val pattern = coronaPattern(text.length, centerPos)
        text.forEachIndexed { i, char ->
            append(pattern.getOrElse(i) { Glow.DARK }.toAnsi())
            append(char)
        }
        append(Ansi.RESET)
    }

    /**
     * Calculates bouncing corona position for animation frames.
     * Creates a "Knight Rider" effect that bounces back and forth.
     *
     * @param textLength Total number of characters
     * @param frameIndex Current animation frame number
     * @return Position for the glow center (0-based)
     */
    fun bouncingCoronaPosition(textLength: Int, frameIndex: Int): Int {
        if (textLength <= 1) return 0
        val cycle = (textLength - 1) * 2
        val pos = frameIndex % cycle
        return if (pos < textLength) pos else cycle - pos
    }

    /**
     * Emits discharge frames continuously with proper timing.
     *
     * In non-interactive mode, emits a single static frame and stops.
     *
     * @param sequence The frame sequence to loop through
     * @return Flow that emits frames indefinitely (or once in non-interactive mode)
     */
    fun dischargeFlow(
        sequence: List<DischargeFrame> = DISCHARGE_SEQUENCE
    ): Flow<DischargeFrame> = flow {
        if (!TerminalSymbols.isInteractive) {
            // Non-interactive mode: emit single static frame
            emit(DischargeFrame(TerminalSymbols.Lightning.staticSymbol, Glow.NORMAL, 0))
            return@flow
        }

        var index = 0
        while (true) {
            val frame = sequence[index % sequence.size]
            emit(frame)
            delay(frame.durationMs)
            index++
        }
    }

    /**
     * Renders a single frame to the terminal (in-place update).
     *
     * In non-interactive mode, prints without cursor control codes.
     *
     * @param frame The frame to render
     * @param message Optional message to display after the symbol
     */
    fun renderFrame(frame: DischargeFrame, message: String = "") {
        if (!TerminalSymbols.isInteractive) {
            // Non-interactive mode: simple print without cursor control
            val suffix = if (message.isNotEmpty()) " $message" else ""
            print("${frame.symbol}$suffix")
            println()
            System.out.flush()
            return
        }

        print("${Ansi.MOVE_TO_START}${Ansi.CLEAR_LINE}")
        print("${frame.glow.toAnsi()}${frame.symbol}${Ansi.RESET}")
        if (message.isNotEmpty()) {
            print(" $message")
        }
        System.out.flush()
    }
}
