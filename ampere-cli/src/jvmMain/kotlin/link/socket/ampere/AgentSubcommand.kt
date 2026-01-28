package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

/**
 * Subcommand for running the autonomous agent test.
 *
 * This is a subcommand of `test`, invoked via `ampere test agent`.
 * It runs an end-to-end demonstration of the PROPEL cognitive cycle.
 *
 * The test demonstrates:
 * 1. Starting the AMPERE environment
 * 2. Creating a CodeWriterAgent
 * 3. Assigning a ticket (task-create command implementation)
 * 4. Agent autonomously running through PROPEL cognitive cycle
 * 5. Generating working Kotlin code
 *
 * Usage:
 *   ampere test agent
 *
 * To observe with the dashboard:
 *   ampere start
 */
class AgentSubcommand : CliktCommand(
    name = "agent",
    help = """
        Run the autonomous agent test - End-to-end PROPEL cognitive cycle demonstration.

        This test demonstrates the complete PROPEL cognitive cycle:
        - Agent perceives a ticket assignment
        - Agent plans the implementation
        - Agent executes code writing
        - Agent learns from the outcome

        The agent will generate code and save it to:
          ~/.ampere/agent-test-output/

        To observe the agent's cognitive cycle in real-time, run the dashboard
        in another terminal:
          ampere start

        Then switch between viewing modes:
          d - Dashboard mode (system vitals, agent status)
          e - Event stream mode (real-time events)
          m - Memory operations mode (knowledge storage)
          1 - Agent focus mode (detailed agent view)

        Options:
          --escalation    Enable human-in-the-loop escalation (prompts for input)

        Examples:
          ampere test agent              # Run the test
          ampere test agent --escalation # Run with human escalation prompts
          ampere test agent --help       # Show this help

        Note: Requires Anthropic API key in local.properties
    """.trimIndent()
) {

    private val escalation: Boolean by option(
        "--escalation",
        help = "Enable human-in-the-loop escalation prompts during cognitive cycle"
    ).flag()

    override fun run() {
        echo("Starting autonomous agent test...")
        if (escalation) {
            echo("Escalation mode enabled - will prompt for input during PLAN phase")
        }
        echo()

        // Call the AgentTestRunner main function with escalation setting
        link.socket.ampere.demo.main(escalation = escalation)
    }
}
