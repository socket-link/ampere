package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Parent command for all demonstration modes.
 *
 * Demos showcase AMPERE capabilities with rich visual feedback,
 * typically using split-pane layouts to show multiple views
 * simultaneously.
 *
 * Usage:
 *   ampere demo jazz    # Run the Jazz Test demo with split-pane view
 */
class DemoCommand : CliktCommand(
    name = "demo",
    help = """
        Run demonstrations showcasing AMPERE capabilities.

        Demo commands provide rich visual feedback using split-pane
        layouts that show multiple views simultaneously - for example,
        execution progress alongside the dashboard.

        Available demos:
          jazz    Run the Jazz Test (autonomous agent demo)
    """.trimIndent()
) {
    override fun run() {
        // Parent command just acts as container
    }
}
