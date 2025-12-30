package link.socket.ampere.cli.animation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

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
    println("\u001b[2J\u001b[H") // Clear screen and move cursor to home
    println("⚡ AMPERE Animation Demo\n")

    // Demo 1: Discharge sequence
    println("1. Discharge Sequence (3 seconds)")
    print("   ")
    runSequenceDemo(LightningAnimator.DISCHARGE_SEQUENCE, 3000)
    println("\n")

    // Demo 2: Compact sequence
    println("2. Compact Sequence (2 seconds)")
    print("   ")
    runSequenceDemo(LightningAnimator.COMPACT_SEQUENCE, 2000)
    println("\n")

    // Demo 3: Corona effect
    println("3. Corona Glow on 'AMPERE' (3 seconds)")
    print("   ")
    runCoronaDemo("AMPERE", 3000)
    println("\n")

    println("Demo complete!")
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
