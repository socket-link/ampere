package link.socket.ampere.cli.animation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs

/**
 * Lightning Animation Primitives for AMPERE CLI.
 *
 * Provides terminal-based lightning effects inspired by electrical discharge:
 * - Discharge cycle sequences (charge → strike → afterglow)
 * - Corona glow effect for text (brightness radiating from center)
 * - ANSI color management for consistent terminal rendering
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
     */
    val DISCHARGE_SEQUENCE = listOf(
        DischargeFrame("·", Glow.DIM, 150),
        DischargeFrame("˙", Glow.DIM, 80),
        DischargeFrame(":", Glow.NORMAL, 80),
        DischargeFrame("⁝", Glow.NORMAL, 70),
        DischargeFrame("⁞", Glow.BRIGHT, 60),
        DischargeFrame("│", Glow.BRIGHT, 50),
        DischargeFrame("╽", Glow.BRIGHT, 40),
        DischargeFrame("ϟ", Glow.FLASH, 120),
        DischargeFrame("✦", Glow.BRIGHT, 100),
        DischargeFrame("✧", Glow.NORMAL, 100),
        DischargeFrame("·", Glow.DIM, 200)
    )

    /**
     * Compact sequence for tighter loops.
     */
    val COMPACT_SEQUENCE = listOf(
        DischargeFrame("·", Glow.DIM, 120),
        DischargeFrame(":", Glow.NORMAL, 80),
        DischargeFrame("⁞", Glow.BRIGHT, 60),
        DischargeFrame("ϟ", Glow.FLASH, 100),
        DischargeFrame("✧", Glow.NORMAL, 100),
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
     * @param sequence The frame sequence to loop through
     * @return Flow that emits frames indefinitely
     */
    fun dischargeFlow(
        sequence: List<DischargeFrame> = DISCHARGE_SEQUENCE
    ): Flow<DischargeFrame> = flow {
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
     * @param frame The frame to render
     * @param message Optional message to display after the symbol
     */
    fun renderFrame(frame: DischargeFrame, message: String = "") {
        print("${Ansi.MOVE_TO_START}${Ansi.CLEAR_LINE}")
        print("${frame.glow.toAnsi()}${frame.symbol}${Ansi.RESET}")
        if (message.isNotEmpty()) {
            print(" $message")
        }
        System.out.flush()
    }
}
