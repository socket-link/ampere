package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Tests for RunCommand, focusing on arc-related flags.
 *
 * Note: These tests verify command-line option parsing and early exit paths.
 * Full integration testing with TUI and agent execution requires manual testing.
 */
class RunCommandTest {

    /**
     * Create a lazy context provider that throws if accessed.
     * Used for help tests where the context shouldn't be needed.
     */
    private fun lazyContext(): AmpereContext =
        error("Context should not be accessed for this test")

    /**
     * Create a test context for use in tests that need actual context.
     */
    private fun createTestContext(tempDir: File): AmpereContext {
        val dbPath = File(tempDir, "test.db").absolutePath
        return AmpereContext(databasePath = dbPath)
    }

    @Test
    fun `run command help text shows arc option`() {
        val command = RunCommand { lazyContext() }
        val result = command.test("--help")

        assertContains(result.output, "--arc")
        assertContains(result.output, "Select arc workflow pattern")
    }

    @Test
    fun `run command help text shows list-arcs option`() {
        val command = RunCommand { lazyContext() }
        val result = command.test("--help")

        assertContains(result.output, "--list-arcs")
        assertContains(result.output, "List available arc configurations")
    }

    @Test
    fun `run command help text shows arc examples`() {
        val command = RunCommand { lazyContext() }
        val result = command.test("--help")

        assertContains(result.output, "ampere run --arc devops-pipeline")
        assertContains(result.output, "ampere run --list-arcs")
    }

    @Test
    fun `list-arcs flag displays available arcs`() {
        val command = RunCommand { lazyContext() }
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
        val command = RunCommand { lazyContext() }
        val result = command.test("--list-arcs")

        assertContains(result.output, "PM -> Code -> QA pipeline")
        assertContains(result.output, "Infrastructure deployment")
    }

    @Test
    fun `list-arcs flag shows agent roles`() {
        val command = RunCommand { lazyContext() }
        val result = command.test("--list-arcs")

        assertContains(result.output, "Agents:")
        assertContains(result.output, "pm → code → qa")
    }

    @Test
    fun `unknown arc name triggers error`() {
        val command = RunCommand { lazyContext() }
        val result = command.test("--arc nonexistent-arc --goal test")

        assertContains(result.output, "Error: Unknown arc 'nonexistent-arc'")
        assertContains(result.output, "Available arcs:")
        assertContains(result.output, "startup-saas")
    }

    @Test
    fun `no work mode still requires a mode even with arc flag`() {
        val command = RunCommand { lazyContext() }
        val result = command.test("--arc startup-saas")

        assertContains(result.output, "Error: No work mode specified")
    }
}
