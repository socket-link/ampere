package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.float
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentLayoutOrientation
import link.socket.ampere.animation.emitter.CognitiveEmitterBridge
import link.socket.ampere.animation.emitter.EmitterManager
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.timeline.PlaybackState
import link.socket.ampere.animation.timeline.TimelineController
import link.socket.ampere.animation.timeline.TimelineEvent
import link.socket.ampere.animation.timeline.WaveformDemoTimeline
import link.socket.ampere.cli.render.WaveformPaneRenderer
import link.socket.ampere.repl.TerminalFactory

/**
 * Container command for demo subcommands.
 *
 * Usage:
 *   ampere demo waveform         Run 3D waveform cognitive cycle demo
 *   ampere demo waveform --loop  Loop the demo continuously
 *   ampere demo waveform --speed 0.5  Run at half speed
 */
class DemoCommand : CliktCommand(
    name = "demo",
    help = "Run visual demos without a live LLM connection"
) {
    init {
        subcommands(WaveformDemoSubcommand())
    }

    override fun run() = Unit
}

/**
 * Runs the 3D cognitive waveform demo sequence in the terminal.
 *
 * The demo exercises the full visual range of the waveform renderer:
 * agent spawning, phase transitions, emitter effects, flow delegation,
 * and completion — all with an orbiting 3D camera.
 */
class WaveformDemoSubcommand : CliktCommand(
    name = "waveform",
    help = "Run 3D cognitive waveform demo (18s sequence)"
) {
    private val speed by option("--speed", "-s", help = "Playback speed multiplier (default 1.0)")
        .float().default(1.0f)

    private val loop by option("--loop", "-l", help = "Loop the demo continuously")
        .flag(default = false)

    override fun run() = runBlocking {
        val caps = TerminalFactory.getCapabilities()
        val width = caps.width.coerceAtLeast(40)
        val height = caps.height.coerceAtLeast(10)
        val contentHeight = height - 2 // Reserve 2 lines for status bar

        // Create shared animation components
        val agents = AgentLayer(width, contentHeight, AgentLayoutOrientation.CIRCULAR)
        val flow = FlowLayer(width, contentHeight)
        val emitters = EmitterManager()
        val emitterBridge = CognitiveEmitterBridge(emitters)
        val substrate = SubstrateState.create(width, contentHeight, baseDensity = 0.2f)

        // Create waveform renderer
        val waveformPane = WaveformPaneRenderer(
            agentLayer = agents,
            emitterManager = emitters,
            cognitiveEmitterBridge = emitterBridge
        )

        // Build timeline and controller
        var timeline = WaveformDemoTimeline.build(agents, flow, emitters)
        var controller = TimelineController(timeline)

        // Track current phase name for status bar
        var currentPhaseName = ""
        controller.addEventListener { event ->
            when (event) {
                is TimelineEvent.PhaseStarted -> currentPhaseName = event.phase.name
                else -> {}
            }
        }
        controller.changeSpeed(speed)

        // Terminal setup
        val out = System.out
        out.print("\u001B[?1049h") // Enter alternate screen buffer
        out.print("\u001B[?25l")   // Hide cursor
        out.print("\u001B[2J")     // Clear screen
        out.flush()

        val frameIntervalMs = 50L // 20 FPS
        val dt = (frameIntervalMs / 1000f) * speed

        try {
            controller.play()

            while (isActive) {
                // Update timeline
                controller.update(frameIntervalMs / 1000f)

                // Update waveform renderer state
                waveformPane.update(substrate, flow, dt)

                // Render the waveform
                val lines = waveformPane.render(width, contentHeight)

                // Build status bar
                val progress = (controller.progress * 100).toInt()
                val phaseDisplay = currentPhaseName.replace('-', ' ')
                val speedDisplay = if (speed != 1.0f) " ${speed}x" else ""
                val loopDisplay = if (loop) " [loop]" else ""
                val statusLine = "\u001B[38;5;45m[$progress%]\u001B[0m " +
                    "\u001B[38;5;226m$phaseDisplay\u001B[0m" +
                    "\u001B[38;5;240m ·$speedDisplay$loopDisplay · q to exit\u001B[0m"
                val separator = "\u001B[38;5;236m${"─".repeat(width)}\u001B[0m"

                // Output frame
                val frame = buildString {
                    append("\u001B[H") // Move cursor to home
                    for (line in lines) {
                        append(line)
                        append("\n")
                    }
                    append(separator)
                    append("\n")
                    append(statusLine)
                }
                out.print(frame)
                out.flush()

                // Check for completion
                if (controller.isCompleted) {
                    if (loop) {
                        // Reset everything for next loop
                        agents.clear()
                        flow.clear()
                        emitters.clear()
                        timeline = WaveformDemoTimeline.build(agents, flow, emitters)
                        controller = TimelineController(timeline)
                        controller.changeSpeed(speed)
                        controller.addEventListener { event ->
                            when (event) {
                                is TimelineEvent.PhaseStarted -> currentPhaseName = event.phase.name
                                else -> {}
                            }
                        }
                        currentPhaseName = ""
                        controller.play()
                    } else {
                        // Show completion for a beat then exit
                        delay(1500)
                        break
                    }
                }

                delay(frameIntervalMs)
            }
        } catch (_: Exception) {
            // Handle Ctrl+C or other interrupts gracefully
        } finally {
            // Restore terminal
            out.print("\u001B[2J")       // Clear screen
            out.print("\u001B[H")        // Move cursor to home
            out.print("\u001B[?25h")     // Show cursor
            out.print("\u001B[?1049l")   // Exit alternate screen buffer
            out.flush()
        }
    }
}
