package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Tests for the unified AmpereCommand, focusing on flag parsing and early exit paths.
 *
 * Note: Full integration testing with TUI and agent execution requires manual testing.
 */
class AmpereCommandTest {

    private fun lazyContext(): AmpereContext =
        error("Context should not be accessed for this test")

    @Test
    fun `help text shows goal option`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--help")

        assertContains(result.output, "--goal")
        assertContains(result.output, "Goal for the agent to work on")
    }

    @Test
    fun `help text shows issues option`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--help")

        assertContains(result.output, "--issues")
        assertContains(result.output, "Work on available GitHub issues")
    }

    @Test
    fun `help text shows arc option`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--help")

        assertContains(result.output, "--arc")
        assertContains(result.output, "Select arc workflow pattern")
    }

    @Test
    fun `help text shows list-arcs option`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--help")

        assertContains(result.output, "--list-arcs")
        assertContains(result.output, "List available arc configurations")
    }

    @Test
    fun `help text shows auto-work option`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--help")

        assertContains(result.output, "--auto-work")
    }

    @Test
    fun `list-arcs flag displays available arcs`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--list-arcs")

        assertContains(result.output, "Available arc configurations")
        assertContains(result.output, "startup-saas")
        assertContains(result.output, "devops-pipeline")
        assertContains(result.output, "research-paper")
        assertContains(result.output, "data-pipeline")
        assertContains(result.output, "security-audit")
        assertContains(result.output, "content-creation")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `list-arcs flag shows arc descriptions`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--list-arcs")

        assertContains(result.output, "PM -> Code -> QA pipeline")
        assertContains(result.output, "Infrastructure deployment")
    }

    @Test
    fun `list-arcs flag shows agent roles`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--list-arcs")

        assertContains(result.output, "Agents:")
        assertContains(result.output, "pm → code → qa")
    }

    @Test
    fun `unknown arc name triggers error`() {
        val command = AmpereCommand { lazyContext() }
        val result = command.test("--arc nonexistent-arc --goal test")

        assertContains(result.output, "Error: Unknown arc 'nonexistent-arc'")
        assertContains(result.output, "Available arcs:")
        assertContains(result.output, "startup-saas")
    }
}
