package link.socket.ampere.cli.animation

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.repl.TerminalFactory

/**
 * Runnable demo that showcases AMPERE lightning animation primitives.
 *
 * Demonstrates:
 * 1. Full discharge sequence (charge → strike → afterglow)
 * 2. Compact discharge sequence (tighter loop)
 * 3. Corona glow effect on text (bouncing brightness)
 *
 * Run with: ./gradlew :ampere-cli:run --args="animation-demo"
 * Or configure as a main class in your IDE.
 */
fun main() = runBlocking {
    val terminal = TerminalFactory.createTerminal()

    try {
        // Initialize terminal for proper animation rendering
        initializeTerminal()

        // Header
        print(terminal.render(TextStyles.bold(TextColors.cyan("⚡ AMPERE Animation Demo"))))
        print("\n\n")
        System.out.flush()
        delay(500)

        // Demo 1: Discharge sequence
        print(terminal.render(TextColors.yellow("1. Discharge Sequence (3 seconds)")))
        print("\n   ")
        System.out.flush()
        runSequenceDemo(LightningAnimator.DISCHARGE_SEQUENCE, 3000)
        print("\n\n")
        System.out.flush()
        delay(500)

        // Demo 2: Compact sequence
        print(terminal.render(TextColors.yellow("2. Compact Sequence (2 seconds)")))
        print("\n   ")
        System.out.flush()
        runSequenceDemo(LightningAnimator.COMPACT_SEQUENCE, 2000)
        print("\n\n")
        System.out.flush()
        delay(500)

        // Demo 3: Corona effect
        print(terminal.render(TextColors.yellow("3. Corona Glow on 'AMPERE' (3 seconds)")))
        print("\n   ")
        System.out.flush()
        runCoronaDemo("AMPERE", 3000)
        print("\n\n")
        System.out.flush()
        delay(500)

        print(terminal.render(TextStyles.bold(TextColors.green("Demo complete!"))))
        print("\n")
        System.out.flush()
        delay(1000)

    } finally {
        restoreTerminal()
    }
}

/**
 * Initialize terminal for animation rendering.
 * - Enter alternate screen buffer
 * - Hide cursor for smooth animations
 * - Clear screen
 */
private fun initializeTerminal() {
    print("\u001B[?1049h")  // Enter alternate screen buffer
    print("\u001B[?25l")    // Hide cursor
    print("\u001B[2J")      // Clear screen
    print("\u001B[H")       // Move cursor to home
    System.out.flush()
}

/**
 * Restore terminal to normal state.
 */
private fun restoreTerminal() {
    print("\u001B[2J")       // Clear screen
    print("\u001B[H")        // Move cursor to home
    print("\u001B[?25h")     // Show cursor
    print("\u001B[?1049l")   // Exit alternate screen buffer
    System.out.flush()
}

/**
 * Runs a discharge animation sequence for a specified duration.
 */
private suspend fun runSequenceDemo(
    sequence: List<LightningAnimator.DischargeFrame>,
    durationMs: Long
) {
    val start = System.currentTimeMillis()
    var index = 0
    while (System.currentTimeMillis() - start < durationMs) {
        val frame = sequence[index % sequence.size]
        print("\u001b[0G   ${frame.glow.toAnsi()}${frame.symbol}\u001b[0m")
        System.out.flush()
        delay(frame.durationMs)
        index++
    }
}

/**
 * Runs a corona glow animation on text for a specified duration.
 */
private suspend fun runCoronaDemo(text: String, durationMs: Long) {
    val start = System.currentTimeMillis()
    var frame = 0
    while (System.currentTimeMillis() - start < durationMs) {
        val pos = LightningAnimator.bouncingCoronaPosition(text.length, frame)
        print("\u001b[0G   ${LightningAnimator.applyCorona(text, pos)}")
        System.out.flush()
        delay(80)
        frame++
    }
}
