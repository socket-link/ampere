package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.executor.acp.HarnessPolicy

/**
 * The OpenCode factory (AMPR-493, D8): the id the record carries, and a binary that is not
 * there reported as unavailable with its reason, never thrown.
 */
class OpenCodeExecutorTest {

    private val posix = File.separatorChar == '/'
    private val root: File = Files.createTempDirectory("opencode-executor-test").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `the factory binds the opencode id and a missing binary is an unavailable health`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val executor = AcpCodeExecutor.openCode(
            binary = File(root, "no-such-opencode").absolutePath,
            workspace = ExecutionWorkspace(baseDirectory = root.absolutePath),
            policy = HarnessPolicy(startTimeout = 10.seconds),
        )

        val health = executor.performHealthCheck().getOrThrow()
        executor.close()

        assertEquals(OPENCODE_EXECUTOR_ID, executor.id)
        assertEquals("opencode", executor.id)
        assertEquals("OpenCode (ACP)", executor.displayName)
        assertFalse(health.isAvailable)
        assertTrue(health.issues.isNotEmpty(), "the reason is reported: ${health.status}")
        assertTrue(executor.capabilities.supportsLanguages.isEmpty(), "language-agnostic")
    }
}
