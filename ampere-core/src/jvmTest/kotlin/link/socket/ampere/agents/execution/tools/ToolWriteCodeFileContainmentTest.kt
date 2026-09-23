package link.socket.ampere.agents.execution.tools

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionContext

/**
 * AMPR-342: `write_code_file` must not write outside the workspace root.
 *
 * The LLM supplies the relative path, so a `../` segment, an absolute
 * path, or a symlink pointing out of the workspace must all be rejected
 * before any filesystem mutation happens.
 */
class ToolWriteCodeFileContainmentTest {

    private lateinit var sandbox: File
    private lateinit var workspace: File

    @BeforeTest
    fun setup() {
        sandbox = Files.createTempDirectory("ampr342").toFile()
        workspace = File(sandbox, "workspace").also { it.mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    private fun writeContext(path: String, content: String) = ExecutionContext.Code.WriteCode(
        executorId = "test-executor",
        ticket = Ticket(
            id = generateUUID(),
            title = "Test ticket",
            description = "Test ticket description",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.Ready,
            assignedAgentId = null,
            createdByAgentId = "test-agent",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        ),
        task = Task.CodeChange(
            id = generateUUID(),
            status = TaskStatus.Pending,
            description = "Test task",
        ),
        instructions = "write",
        workspace = ExecutionWorkspace(baseDirectory = workspace.path),
        instructionsPerFilePath = listOf(path to content),
    )

    private fun assertRejected(outcome: ExecutionOutcome.CodeChanged) {
        assertIs<ExecutionOutcome.CodeChanged.Failure>(outcome)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, outcome.error.type)
        assertTrue(
            outcome.error.message.contains("outside root directory"),
            "unexpected error message: ${outcome.error.message}",
        )
        assertTrue(outcome.partiallyChangedFiles.isNullOrEmpty(), "nothing should be reported as written")
    }

    @Test
    fun `parent traversal path is rejected and nothing is written outside the workspace`() = runTest {
        val escaped = File(sandbox, "escaped.txt")
        val original = "original"
        escaped.writeText(original)

        val outcome = executeWriteCodeFile(writeContext("../escaped.txt", "overwritten"))

        assertRejected(outcome)
        assertEquals(original, escaped.readText(), "file outside the workspace must be untouched")
    }

    @Test
    fun `nested parent traversal path is rejected`() = runTest {
        val outcome = executeWriteCodeFile(writeContext("src/../../escaped.txt", "x"))

        assertRejected(outcome)
        assertFalse(File(sandbox, "escaped.txt").exists())
    }

    @Test
    fun `absolute path is re-rooted inside the workspace and never reaches its target`() = runTest {
        val target = File(sandbox, "absolute.txt")

        val outcome = executeWriteCodeFile(writeContext(target.absolutePath, "x"))

        // java.io.File(parent, "/abs") joins onto the parent, so the write is contained
        // under the workspace rather than reaching the absolute location.
        assertIs<ExecutionOutcome.CodeChanged.Success>(outcome)
        assertFalse(target.exists(), "absolute target outside the workspace must be untouched")
        val contained = File(workspace, target.absolutePath)
        assertTrue(contained.canonicalPath.startsWith(workspace.canonicalPath + File.separator))
        assertEquals("x", contained.readText())
    }

    @Test
    fun `symlink pointing outside the workspace is rejected`() = runTest {
        val outside = File(sandbox, "outside").also { it.mkdirs() }
        val link = File(workspace, "link")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            return@runTest // filesystem does not support symlinks; nothing to verify
        }

        val outcome = executeWriteCodeFile(writeContext("link/escaped.txt", "x"))

        assertRejected(outcome)
        assertFalse(File(outside, "escaped.txt").exists())
    }

    @Test
    fun `relative path inside the workspace is written and parent directories are created`() = runTest {
        val outcome = executeWriteCodeFile(writeContext("src/main/Hello.kt", "fun main() {}"))

        assertIs<ExecutionOutcome.CodeChanged.Success>(outcome)
        assertEquals(listOf("src/main/Hello.kt"), outcome.changedFiles)
        assertEquals("fun main() {}", File(workspace, "src/main/Hello.kt").readText())
    }

    @Test
    fun `path that traverses up and back into the workspace is allowed`() = runTest {
        val outcome = executeWriteCodeFile(writeContext("../workspace/inside.txt", "ok"))

        assertIs<ExecutionOutcome.CodeChanged.Success>(outcome)
        assertEquals("ok", File(workspace, "inside.txt").readText())
    }
}
