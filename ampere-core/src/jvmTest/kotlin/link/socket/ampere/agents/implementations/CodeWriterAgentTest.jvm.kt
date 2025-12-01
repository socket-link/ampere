package link.socket.ampere.agents.implementations

import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider

@OptIn(ExperimentalCoroutinesApi::class)
actual class CodeWriterAgentTest {

    private val stubTicket = Ticket(
        id = "TestTicket",
        title = "TestTitle",
        description = "TestDescription",
        type = TicketType.TASK,
        priority = TicketPriority.LOW,
        status = TicketStatus.Ready,
        assignedAgentId = "TestAgent",
        createdByAgentId = "TestAgent",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now() + 1.seconds,
        dueDate = Clock.System.now() + 1.minutes,
    )

    private val stubTask = Task.CodeChange(
        id = "TestTask",
        status = TaskStatus.InProgress,
        description = "TestDescription",
        assignedTo = AssignedTo.Agent("TestAgent"),
    )

    private val stubTool = ToolWriteCodeFile(
        requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
    )

    private lateinit var executionRequest: ExecutionRequest<ExecutionContext.Code.WriteCode>

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var tempDir: Path

    // ==================== FAKE IMPLEMENTATIONS ====================

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Not needed for tests")
        override val model: AIModel
            get() = throw NotImplementedError("Not needed for tests")

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory(prefix = "ampere-agent-test-")

        executionRequest = ExecutionRequest(
            context = ExecutionContext.Code.WriteCode(
                executorId = "executor-1",
                ticket = stubTicket,
                task = stubTask,
                instructions = "Write a function",
                workspace = ExecutionWorkspace(tempDir.absolutePathString()),
                instructionsPerFilePath = listOf()
            ),
            constraints = ExecutionConstraints(),
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // TODO: Write tests
}
