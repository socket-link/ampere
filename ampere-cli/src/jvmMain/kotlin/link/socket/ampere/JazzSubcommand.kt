package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Subcommand for running the Jazz Test.
 *
 * This is a subcommand of `test`, invoked via `ampere test jazz`.
 * It runs the same Jazz Test as the standalone `ampere jazz-test` command.
 *
 * The Jazz Test demonstrates:
 * 1. Starting the AMPERE environment
 * 2. Creating a CodeWriterAgent
 * 3. Assigning a ticket (task-create command implementation)
 * 4. Agent autonomously running through PROPEL cognitive cycle
 * 5. Generating working Kotlin code
 *
 * Usage:
 *   ampere test jazz
 *
 * To observe with the dashboard:
 *   ampere start
 */
class JazzSubcommand : CliktCommand(
    name = "jazz",
    help = """
        Run the Jazz Test - End-to-end autonomous agent demonstration.

        This test demonstrates the complete PROPEL cognitive cycle:
        - Agent perceives a ticket assignment
        - Agent plans the implementation
        - Agent executes code writing
        - Agent learns from the outcome

        The agent will generate a new TaskCommand and save it to:
          ~/.ampere/jazz-test-output/ampere-cli/src/jvmMain/kotlin/link/socket/ampere/TaskCommand.kt

        To observe the agent's cognitive cycle in real-time, run the dashboard
        in another terminal:
          ampere start

        Then switch between viewing modes:
          d - Dashboard mode (system vitals, agent status)
          e - Event stream mode (real-time events)
          m - Memory operations mode (knowledge storage)
          1 - Agent focus mode (detailed agent view)

        Examples:
          ampere test jazz              # Run the test
          ampere test jazz --help       # Show this help

        Note: Requires Anthropic API key in local.properties
    """.trimIndent()
) {

    override fun run() {
        echo("Starting Jazz Test...")
        echo()

        // Call the JazzTestRunner main function
        link.socket.ampere.demo.main()
    }
}
