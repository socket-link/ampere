package link.socket.ampere.agents.definition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.integrations.issues.ExistingIssue
import link.socket.ampere.integrations.issues.IssueQuery
import link.socket.ampere.integrations.issues.IssueState
import link.socket.ampere.integrations.issues.IssueTrackerProvider
import link.socket.ampere.integrations.issues.IssueUpdate
import link.socket.ampere.stubAgentConfiguration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for CodeAgent issue discovery functionality.
 *
 * Verifies that CodeAgent can:
 * - Query GitHub for assigned issues
 * - Query GitHub for available unassigned issues
 * - Include issue information in perception context
 * - Handle missing provider gracefully
 */
class CodeAgentIssueDiscoveryTest {

    private class MockIssueTrackerProvider : IssueTrackerProvider {
        override val providerId: String = "mock-github"
        override val displayName: String = "Mock GitHub"

        var assignedIssues: List<ExistingIssue> = emptyList()
        var availableIssues: List<ExistingIssue> = emptyList()
        var lastQuery: IssueQuery? = null
        var shouldFail: Boolean = false

        override suspend fun validateConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun createIssue(
            repository: String,
            request: link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest,
            resolvedDependencies: Map<String, Int>,
        ): Result<link.socket.ampere.agents.execution.tools.issue.CreatedIssue> {
            error("Not implemented for this test")
        }

        override suspend fun setParentRelationship(
            repository: String,
            childIssueNumber: Int,
            parentIssueNumber: Int,
        ): Result<Unit> {
            error("Not implemented for this test")
        }

        override suspend fun queryIssues(
            repository: String,
            query: IssueQuery,
        ): Result<List<ExistingIssue>> {
            lastQuery = query

            if (shouldFail) {
                return Result.failure(Exception("Mock query failure"))
            }

            // Return assigned or available issues based on query
            return Result.success(
                when {
                    query.assignee == "CodeWriterAgent" -> assignedIssues
                    query.assignee == null -> availableIssues
                    else -> emptyList()
                },
            )
        }

        override suspend fun updateIssue(
            repository: String,
            issueNumber: Int,
            update: IssueUpdate,
        ): Result<ExistingIssue> {
            error("Not implemented for this test")
        }
    }

    private fun createTestAgent(
        issueTrackerProvider: IssueTrackerProvider? = null,
        repository: String? = null,
    ): CodeAgent {
        val mockWriteTool = FunctionTool<ExecutionContext.Code.WriteCode>(
            id = "write_code_file",
            name = "write_code_file",
            description = "Write code to a file",
            requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
            executionFunction = { error("Not used in this test") },
        )

        return CodeAgent(
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockWriteTool,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            issueTrackerProvider = issueTrackerProvider,
            repository = repository,
        )
    }

    @Test
    fun `queryAssignedIssues returns empty when provider is null`() = runTest {
        val agent = createTestAgent(
            issueTrackerProvider = null,
            repository = "test/repo",
        )

        val result = agent.queryAssignedIssues()

        assertTrue(result.isEmpty(), "Should return empty list when provider is null")
    }

    @Test
    fun `queryAssignedIssues returns empty when repository is null`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = null,
        )

        val result = agent.queryAssignedIssues()

        assertTrue(result.isEmpty(), "Should return empty list when repository is null")
    }

    @Test
    fun `queryAssignedIssues queries with correct parameters`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        mockProvider.assignedIssues = listOf(
            ExistingIssue(
                number = 123,
                title = "Fix authentication bug",
                body = "Auth is broken in production",
                state = IssueState.Open,
                labels = listOf("bug", "priority-high"),
                url = "https://github.com/test/repo/issues/123",
            ),
        )

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val result = agent.queryAssignedIssues()

        // Verify query parameters
        val lastQuery = mockProvider.lastQuery
        assertTrue(lastQuery != null, "Query should have been called")
        assertTrue(lastQuery!!.state == IssueState.Open, "Should query for open issues")
        assertTrue(lastQuery.assignee == "CodeWriterAgent", "Should query for CodeWriterAgent")
        assertTrue(lastQuery.limit == 20, "Should limit to 20 results")

        // Verify results
        assertTrue(result.size == 1, "Should return 1 assigned issue")
    }

    @Test
    fun `queryAssignedIssues returns empty on provider failure`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        mockProvider.shouldFail = true

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val result = agent.queryAssignedIssues()

        assertTrue(result.isEmpty(), "Should return empty list on failure")
    }

    @Test
    fun `queryAvailableIssues returns empty when provider is null`() = runTest {
        val agent = createTestAgent(
            issueTrackerProvider = null,
            repository = "test/repo",
        )

        val result = agent.queryAvailableIssues()

        assertTrue(result.isEmpty(), "Should return empty list when provider is null")
    }

    @Test
    fun `queryAvailableIssues queries with correct parameters`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        mockProvider.availableIssues = listOf(
            ExistingIssue(
                number = 456,
                title = "Add new feature",
                body = "Implement user preferences",
                state = IssueState.Open,
                labels = listOf("code", "feature"),
                url = "https://github.com/test/repo/issues/456",
            ),
        )

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val result = agent.queryAvailableIssues()

        // Verify query parameters
        val lastQuery = mockProvider.lastQuery
        assertTrue(lastQuery != null, "Query should have been called")
        assertTrue(lastQuery!!.state == IssueState.Open, "Should query for open issues")
        assertTrue(lastQuery.assignee == null, "Should query for unassigned issues")
        assertTrue(lastQuery.labels.contains("code"), "Should filter by 'code' label")
        assertTrue(lastQuery.labels.contains("task"), "Should filter by 'task' label")
        assertTrue(lastQuery.limit == 10, "Should limit to 10 results")

        // Verify results
        assertTrue(result.size == 1, "Should return 1 available issue")
    }

    @Test
    fun `perception context includes assigned issues section when issues exist`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        mockProvider.assignedIssues = listOf(
            ExistingIssue(
                number = 123,
                title = "Fix authentication bug",
                body = "Auth is broken in production. Need to update JWT validation.",
                state = IssueState.Open,
                labels = listOf("bug", "priority-high"),
                url = "https://github.com/test/repo/issues/123",
            ),
            ExistingIssue(
                number = 124,
                title = "Refactor database layer",
                body = "",
                state = IssueState.Open,
                labels = listOf("refactor"),
                url = "https://github.com/test/repo/issues/124",
            ),
        )

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val context = agent.buildPerceptionContext(CodeState.blank)

        assertTrue(context.isNotBlank(), "Context should not be blank")
        assertTrue(context.contains("Assigned Issues"), "Should have Assigned Issues section")
        assertTrue(context.contains("#123: Fix authentication bug"), "Should include issue #123")
        assertTrue(context.contains("#124: Refactor database layer"), "Should include issue #124")
        assertTrue(context.contains("bug, priority-high"), "Should include labels")
        assertTrue(context.contains("https://github.com/test/repo/issues/123"), "Should include URL")
        assertTrue(context.contains("Auth is broken in production"), "Should include description preview")
    }

    @Test
    fun `perception context includes available issues section when issues exist`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        mockProvider.availableIssues = listOf(
            ExistingIssue(
                number = 456,
                title = "Add dark mode",
                body = "Users want dark mode",
                state = IssueState.Open,
                labels = listOf("code", "feature"),
                url = "https://github.com/test/repo/issues/456",
            ),
        )

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val context = agent.buildPerceptionContext(CodeState.blank)

        assertTrue(context.isNotBlank(), "Context should not be blank")
        assertTrue(
            context.contains("Available Issues (Unassigned)"),
            "Should have Available Issues section",
        )
        assertTrue(context.contains("#456: Add dark mode"), "Should include issue #456")
        assertTrue(context.contains("code, feature"), "Should include labels")
    }

    @Test
    fun `perception context limits available issues to 5 with overflow message`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        mockProvider.availableIssues = (1..8).map { i ->
            ExistingIssue(
                number = i,
                title = "Issue $i",
                body = "Description $i",
                state = IssueState.Open,
                labels = listOf("code"),
                url = "https://github.com/test/repo/issues/$i",
            )
        }

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val context = agent.buildPerceptionContext(CodeState.blank)

        assertTrue(context.isNotBlank(), "Context should not be blank")
        assertTrue(context.contains("#1: Issue 1"), "Should include first issue")
        assertTrue(context.contains("#5: Issue 5"), "Should include fifth issue")
        assertTrue(context.contains("... and 3 more available"), "Should show overflow message")
        assertFalse(context.contains("#6: Issue 6"), "Should not include sixth issue")
    }

    @Test
    fun `perception context excludes issue sections when provider is null`() = runTest {
        val agent = createTestAgent(
            issueTrackerProvider = null,
            repository = "test/repo",
        )

        val context = agent.buildPerceptionContext(CodeState.blank)

        assertTrue(context.isNotBlank(), "Context should not be blank")
        assertFalse(
            context.contains("Assigned Issues"),
            "Should not have Assigned Issues section",
        )
        assertFalse(
            context.contains("Available Issues"),
            "Should not have Available Issues section",
        )
    }

    @Test
    fun `perception context excludes issue sections when repository is null`() = runTest {
        val agent = createTestAgent(
            issueTrackerProvider = MockIssueTrackerProvider(),
            repository = null,
        )

        val context = agent.buildPerceptionContext(CodeState.blank)

        assertTrue(context.isNotBlank(), "Context should not be blank")
        assertFalse(
            context.contains("Assigned Issues"),
            "Should not have Assigned Issues section",
        )
        assertFalse(
            context.contains("Available Issues"),
            "Should not have Available Issues section",
        )
    }

    @Test
    fun `perception context excludes issue sections when queries return empty`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        // Empty lists by default

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val context = agent.buildPerceptionContext(CodeState.blank)

        assertTrue(context.isNotBlank(), "Context should not be blank")
        assertFalse(
            context.contains("Assigned Issues"),
            "Should not have Assigned Issues section when empty",
        )
        assertFalse(
            context.contains("Available Issues"),
            "Should not have Available Issues section when empty",
        )
    }

    @Test
    fun `perception context handles long issue descriptions with preview`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        val longDescription = "This is a very long issue description ".repeat(10) // > 100 chars
        mockProvider.assignedIssues = listOf(
            ExistingIssue(
                number = 999,
                title = "Long description issue",
                body = longDescription,
                state = IssueState.Open,
                labels = listOf("bug"),
                url = "https://github.com/test/repo/issues/999",
            ),
        )

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val context = agent.buildPerceptionContext(CodeState.blank)

        assertTrue(context.isNotBlank(), "Context should not be blank")
        assertTrue(context.contains("Description: This is a very long"), "Should include preview")
        assertTrue(context.contains("..."), "Should include ellipsis for long description")
    }

    @Test
    fun `perception context handles issue with blank description`() = runTest {
        val mockProvider = MockIssueTrackerProvider()
        mockProvider.assignedIssues = listOf(
            ExistingIssue(
                number = 100,
                title = "Issue with no description",
                body = "",
                state = IssueState.Open,
                labels = listOf("task"),
                url = "https://github.com/test/repo/issues/100",
            ),
        )

        val agent = createTestAgent(
            issueTrackerProvider = mockProvider,
            repository = "test/repo",
        )

        val context = agent.buildPerceptionContext(CodeState.blank)

        assertTrue(context.isNotBlank(), "Context should not be blank")
        assertTrue(context.contains("#100: Issue with no description"), "Should include issue")
        assertFalse(
            context.contains("Description:"),
            "Should not include description line when blank",
        )
    }
}
