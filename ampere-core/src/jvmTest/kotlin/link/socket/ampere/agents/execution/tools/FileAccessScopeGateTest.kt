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
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest

/**
 * AMPR-414: `write_code_file` and `read_code_file` refuse a path the spark
 * stack does not permit, and refuse it *before* touching the filesystem.
 *
 * The refusal is an `ExecutionOutcome.*.Failure` and never a thrown
 * exception, per the tool-error convention in AGENTS.md — an exception out of
 * an execution function is caught generically by the engine and reported as
 * "tool execution failed", which tells an agent nothing it could act on.
 *
 * This gate is about *which paths within* a workspace may be touched.
 * Workspace containment (AMPR-300 / AMPR-342) is a separate check at the
 * platform layer, and `ToolWriteCodeFileContainmentTest` covers it; the two
 * compose and neither replaces the other.
 */
class FileAccessScopeGateTest {

    private lateinit var workspace: File

    private val kotlinOnly = FileAccessScope(
        readPatterns = setOf("**/*.kt"),
        writePatterns = setOf("**/*.kt"),
        forbiddenPatterns = setOf("**/build/**") + FileAccessScope.SensitiveFileForbiddenPatterns,
    )

    @BeforeTest
    fun setup() {
        workspace = Files.createTempDirectory("ampr414").toFile()
    }

    @AfterTest
    fun tearDown() {
        workspace.deleteRecursively()
    }

    // ==================== WRITES ====================

    @Test
    fun `a write outside the permitted patterns fails without touching the filesystem`() = runTest {
        val outcome = write("docs/NOTES.md", "nope", scope = kotlinOnly)

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(outcome)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, failure.error.type)
        assertTrue(
            failure.error.message.contains("no write pattern permits it"),
            "the refusal should say which rule refused; got: ${failure.error.message}",
        )
        assertEquals(emptyList(), failure.partiallyChangedFiles)
        assertFalse(File(workspace, "docs/NOTES.md").exists(), "nothing should have been written")
        assertFalse(File(workspace, "docs").exists(), "not even the parent directory")
    }

    @Test
    fun `a write to a forbidden path fails even though an allow pattern matches it`() = runTest {
        val outcome = write("build/generated/Thing.kt", "nope", scope = kotlinOnly)

        val failure = assertIs<ExecutionOutcome.CodeChanged.Failure>(outcome)
        assertTrue(
            failure.error.message.contains("**/build/**"),
            "the refusal should name the forbidden pattern; got: ${failure.error.message}",
        )
        assertFalse(File(workspace, "build/generated/Thing.kt").exists())
    }

    @Test
    fun `a batch is refused whole when one of its paths is out of scope`() = runTest {
        val outcome = write(
            paths = listOf("src/Allowed.kt" to "ok", ".env" to "SECRET=1"),
            scope = kotlinOnly,
        )

        assertIs<ExecutionOutcome.CodeChanged.Failure>(outcome)
        assertFalse(
            File(workspace, "src/Allowed.kt").exists(),
            "a half-applied batch is worse than a refused one",
        )
        assertFalse(File(workspace, ".env").exists())
    }

    @Test
    fun `a permitted write still goes through`() = runTest {
        val outcome = write("src/Thing.kt", "fun thing() {}", scope = kotlinOnly)

        assertIs<ExecutionOutcome.CodeChanged.Success>(outcome)
        assertEquals("fun thing() {}", File(workspace, "src/Thing.kt").readText())
    }

    @Test
    fun `a request with no scope is unconstrained`() = runTest {
        val outcome = write("docs/NOTES.md", "fine", scope = null)

        assertIs<ExecutionOutcome.CodeChanged.Success>(outcome)
        assertEquals("fine", File(workspace, "docs/NOTES.md").readText())
    }

    // ==================== READS ====================

    @Test
    fun `a read outside the permitted patterns fails without opening the file`() = runTest {
        File(workspace, "secrets.json").writeText("""{"token":"shh"}""")

        val outcome = read("secrets.json", scope = kotlinOnly)

        val failure = assertIs<ExecutionOutcome.CodeReading.Failure>(outcome)
        assertEquals(ExecutionError.Type.WORKSPACE_ERROR, failure.error.type)
        assertEquals(emptyList(), failure.partiallyReadFiles)
        assertFalse(
            failure.error.message.contains("shh"),
            "the refusal must not carry the content it refused to read",
        )
    }

    @Test
    fun `a read of a forbidden path fails even when a read pattern matches it`() = runTest {
        File(workspace, "build").mkdirs()
        File(workspace, "build/Generated.kt").writeText("// generated")

        val outcome = read("build/Generated.kt", scope = kotlinOnly)

        val failure = assertIs<ExecutionOutcome.CodeReading.Failure>(outcome)
        assertTrue(
            failure.error.message.contains("**/build/**"),
            "the refusal should name the forbidden pattern; got: ${failure.error.message}",
        )
    }

    @Test
    fun `a permitted read still goes through`() = runTest {
        File(workspace, "src").mkdirs()
        File(workspace, "src/Thing.kt").writeText("fun thing() {}")

        val outcome = read("src/Thing.kt", scope = kotlinOnly)

        val success = assertIs<ExecutionOutcome.CodeReading.Success>(outcome)
        assertEquals(listOf("src/Thing.kt" to "fun thing() {}"), success.readFiles)
    }

    // ==================== HARNESS ====================

    private suspend fun write(
        path: String,
        content: String,
        scope: FileAccessScope?,
    ): ExecutionOutcome = write(listOf(path to content), scope)

    private suspend fun write(
        paths: List<Pair<String, String>>,
        scope: FileAccessScope?,
    ): ExecutionOutcome {
        val tool = ToolWriteCodeFile(
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            parameterStrategy = null,
        )
        val request = ExecutionRequest(
            context = ExecutionContext.Code.WriteCode(
                executorId = EXECUTOR_ID,
                ticket = ticket(),
                task = task(),
                instructions = "write",
                workspace = ExecutionWorkspace(baseDirectory = workspace.path),
                instructionsPerFilePath = paths,
            ),
            constraints = ExecutionConstraints(),
            fileAccessScope = scope,
        )
        return tool.execute(request) as ExecutionOutcome
    }

    private suspend fun read(path: String, scope: FileAccessScope?): ExecutionOutcome {
        val tool = ToolReadCodeFile(parameterStrategy = null)
        val request = ExecutionRequest(
            context = ExecutionContext.Code.ReadCode(
                executorId = EXECUTOR_ID,
                ticket = ticket(),
                task = task(),
                instructions = "read",
                workspace = ExecutionWorkspace(baseDirectory = workspace.path),
                filePathsToRead = listOf(path),
            ),
            constraints = ExecutionConstraints(),
            fileAccessScope = scope,
        )
        return tool.execute(request) as ExecutionOutcome
    }

    private fun ticket(): Ticket {
        val now = Clock.System.now()
        return Ticket(
            id = "ticket-414",
            title = "Test ticket",
            description = "Test ticket description",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = EXECUTOR_ID,
            createdByAgentId = EXECUTOR_ID,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun task(): Task.CodeChange = Task.CodeChange(
        id = "task-414",
        status = TaskStatus.Pending,
        description = "touch a file",
    )

    private companion object {
        const val EXECUTOR_ID = "gate-test-executor"
    }
}
