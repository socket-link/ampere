package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import link.socket.ampere.demo.main as runJazzTest

/**
 * CLI command to run the Jazz Test - an end-to-end autonomous agent demonstration.
 *
 * This command runs the complete Jazz Test scenario:
 * 1. Starts the AMPERE environment
 * 2. Creates a CodeWriterAgent
 * 3. Creates and assigns a Fibonacci ticket
 * 4. Agent autonomously runs through the PROPEL cognitive cycle
 * 5. Generates working Kotlin code
 *
 * Usage:
 *   ampere jazz-test
 *
 * To observe with the dashboard, run in another terminal:
 *   ampere start
 */
class JazzTestCommand : CliktCommand(
    name = "jazz-test",
    help = """
        Run the Jazz Test - End-to-end autonomous agent demonstration.

        This test demonstrates the complete PROPEL cognitive cycle:
        - Agent perceives a ticket assignment
        - Agent plans the implementation
        - Agent executes code writing
        - Agent learns from the outcome

        The agent will generate a Fibonacci function in Kotlin and save it to:
          ~/.ampere/jazz-test-output/Fibonacci.kt

        To observe the agent's cognitive cycle in real-time, run the dashboard
        in another terminal:
          ampere start

        Then switch between viewing modes:
          d - Dashboard mode (system vitals, agent status)
          e - Event stream mode (real-time events)
          m - Memory operations mode (knowledge storage)
          1 - Agent focus mode (detailed agent view)

        Examples:
          ampere jazz-test              # Run the test
          ampere jazz-test --help       # Show this help

        Note: Requires Anthropic API key in local.properties
    """.trimIndent()
) {

    override fun run() {
        echo("Starting Jazz Test...")
        echo()

        // Call the JazzTestRunner main function
        runJazzTest()
    }
}
