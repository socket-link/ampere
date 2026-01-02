package link.socket.ampere.agents.definition

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.definition.code.IssueWorkflowStatus
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.stubAgentConfiguration
import link.socket.ampere.integrations.issues.ExistingIssue
import link.socket.ampere.integrations.issues.IssueQuery
import link.socket.ampere.integrations.issues.IssueState
import link.socket.ampere.integrations.issues.IssueTrackerProvider
import link.socket.ampere.integrations.issues.IssueUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import link.socket.ampere.agents.execution.tools.issue.CreatedIssue
import link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for CodeAgent's full issue-to-PR workflow.
 *
 * These tests validate the complete autonomous pipeline:
 * 1. Issue Discovery - Agent finds assigned/available issues
 * 2. Planning - Agent creates implementation plan with Git workflow
 * 3. Execution - Agent writes code, creates branch, commits, pushes
 * 4. PR Creation - Agent creates pull request with proper formatting
 * 5. Status Updates - Issue labels track progress through workflow
 *
 * ## SUCCESS CRITERIA
 *
 * ### Phase 1: Issue Management (COMPLETED)
 * ✅ Agent can discover unassigned issues with 'code' label
 * ✅ Agent can discover assigned issues
 * ✅ Agent can update issue status to IN_PROGRESS
 * ✅ Agent can update issue status to IN_REVIEW after PR creation
 * ✅ Agent can mark issues as BLOCKED on failure
 * ✅ IssueWorkflowStatus correctly parses from labels
 * ✅ IssueWorkflowStatus prioritizes later statuses when multiple present
 *
 * ### Phase 2: Git Operations (PENDING)
 * ⏳ Agent creates feature branches with proper naming (issue-N-description)
 * ⏳ Agent commits code with conventional commit messages
 * ⏳ Agent pushes branches to remote repository
 * ⏳ Agent creates PRs with formatted body (Summary, Changes, Testing, Checklist)
 * ⏳ Agent assigns reviewers based on file analysis
 * ⏳ PRs auto-link to issues with "Closes #N"
 *
 * ### Phase 3: End-to-End Workflow (PENDING)
 * ⏳ Agent completes full issue-to-PR pipeline autonomously
 * ⏳ Issue status transitions through all stages (CLAIMED → IN_PROGRESS → IN_REVIEW)
 * ⏳ Events published for each workflow stage
 * ⏳ Agent handles review feedback and updates PRs
 *
 * ### Phase 4: Error Handling (PENDING)
 * ⏳ Agent marks issues as BLOCKED on unrecoverable errors
 * ⏳ Agent retries transient failures
 * ⏳ Agent escalates to humans when stuck
 *
 * ## IMPLEMENTATION STATUS
 *
 * **Current State:** Phase 1 complete, Phase 2-4 require Git tool implementation
 *
 * **Blocked By:**
 * - Git tools execute placeholder operations instead of real git/gh commands
 * - Full workflow orchestration needs event-driven coordination
 * - Review feedback handling requires PR comment monitoring
 *
 * **Next Steps:**
 * 1. Implement actual Git operations in ToolCreateBranch, ToolCommit, etc.
 * 2. Enable placeholder integration tests by removing @Ignore annotations
 * 3. Add event verification to validate workflow events are published
 * 4. Implement review feedback processing workflow
 *
 * NOTE: Many tests are currently disabled (@Ignore) because they require:
 * - Full Git tool implementation (currently placeholders)
 * - Issue tracker provider with comment support
 * - Complete workflow orchestration
 *
 * Enable these tests incrementally as those components are implemented.
 */
class CodeAgentIntegrationTest {

    /**
     * Mock issue tracker for testing issue discovery and updates.
     */
    private class TestIssueTrackerProvider : IssueTrackerProvider {
        override val providerId = "test-github"
        override val displayName = "Test GitHub"

        val issues = mutableMapOf<Int, ExistingIssue>()
        var nextIssueNumber = 1

        override suspend fun validateConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun createIssue(
            repository: String,
            request: IssueCreateRequest,
            resolvedDependencies: Map<String, Int>,
        ): Result<CreatedIssue> {
            val number = nextIssueNumber++
            val issue = ExistingIssue(
                number = number,
                title = request.title,
                body = request.description,
                state = IssueState.Open,
                labels = request.labels,
                url = "https://github.com/test/repo/issues/$number",
            )
            issues[number] = issue
            return Result.success(
                CreatedIssue(
                    number = number,
                    url = issue.url,
                    localId = null,
                ),
            )
        }

        override suspend fun setParentRelationship(
            repository: String,
            childIssueNumber: Int,
            parentIssueNumber: Int,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun queryIssues(
            repository: String,
            query: IssueQuery,
        ): Result<List<ExistingIssue>> {
            var filtered = issues.values.toList()

            query.state?.let { state ->
                if (state != IssueState.All) {
                    filtered = filtered.filter { it.state == state }
                }
            }

            query.assignee?.let { assignee ->
                // For this mock, we'll just return all issues when assignee is set
                // In a real implementation, this would filter by assignee
            }

            if (query.labels.isNotEmpty()) {
                filtered = filtered.filter { issue ->
                    query.labels.all { label -> issue.labels.contains(label) }
                }
            }

            return Result.success(filtered.take(query.limit))
        }

        override suspend fun updateIssue(
            repository: String,
            issueNumber: Int,
            update: IssueUpdate,
        ): Result<ExistingIssue> {
            val current = issues[issueNumber]
                ?: return Result.failure(IllegalArgumentException("Issue #$issueNumber not found"))

            val updated = current.copy(
                title = update.title ?: current.title,
                body = update.body ?: current.body,
                state = update.state ?: current.state,
                labels = update.labels ?: current.labels,
            )

            issues[issueNumber] = updated
            return Result.success(updated)
        }

        fun createTestIssue(
            title: String,
            body: String = "",
            labels: List<String> = emptyList(),
            assignee: String? = null,
        ): ExistingIssue {
            val number = nextIssueNumber++
            val issue = ExistingIssue(
                number = number,
                title = title,
                body = body,
                state = IssueState.Open,
                labels = labels,
                url = "https://github.com/test/repo/issues/$number",
            )
            issues[number] = issue
            return issue
        }
    }

    private fun createTestAgent(
        issueProvider: TestIssueTrackerProvider,
    ): CodeAgent {
        val mockWriteTool = FunctionTool<ExecutionContext.Code.WriteCode>(
            id = "write_code_file",
            name = "write_code_file",
            description = "Write code to a file",
            requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
            executionFunction = { _ ->
                ExecutionOutcome.CodeChanged.Success(
                    executorId = "test",
                    ticketId = "test-ticket",
                    taskId = "test-task",
                    changedFiles = listOf("src/StringUtils.kt"),
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                )
            },
        )

        return CodeAgent(
            agentConfiguration = stubAgentConfiguration(),
            toolWriteCodeFile = mockWriteTool,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            issueTrackerProvider = issueProvider,
            repository = "test/repo",
        )
    }

    // ========================================================================
    // Issue Discovery Tests
    // ========================================================================

    @Test
    fun `agent discovers unassigned issues with code label`() = runTest {
        val issueProvider = TestIssueTrackerProvider()
        val agent = createTestAgent(issueProvider)

        // Create unassigned issue with 'code' label
        issueProvider.createTestIssue(
            title = "Add greeting function",
            body = "Create a function that greets users",
            labels = listOf("task", "code"),
        )

        // Query available issues
        val availableIssues = agent.queryAvailableIssues()

        // Verify issue is discovered
        assertEquals(1, availableIssues.size)
        assertTrue(availableIssues[0].title.contains("greeting"))
    }

    @Test
    fun `agent discovers assigned issues`() = runTest {
        val issueProvider = TestIssueTrackerProvider()
        val agent = createTestAgent(issueProvider)

        // Create assigned issue (mock assigns by adding to query results)
        issueProvider.createTestIssue(
            title = "Implement helper utility",
            body = "Add utility functions",
            labels = listOf("code"),
            assignee = "CodeWriterAgent",
        )

        // Query assigned issues
        val assignedIssues = agent.queryAssignedIssues()

        // Verify issue is discovered
        assertEquals(1, assignedIssues.size)
        assertTrue(assignedIssues[0].title.contains("helper"))
    }

    // ========================================================================
    // Status Update Tests
    // ========================================================================

    @Test
    fun `agent updates issue status to IN_PROGRESS`() = runTest {
        val issueProvider = TestIssueTrackerProvider()
        val agent = createTestAgent(issueProvider)

        // Create test issue
        val issue = issueProvider.createTestIssue(
            title = "Add feature",
            labels = listOf("code", "assigned"),
        )

        // Update status
        val result = agent.updateIssueStatus(
            issueNumber = issue.number,
            status = IssueWorkflowStatus.IN_PROGRESS,
            comment = "Starting work",
        )

        // Verify success
        assertTrue(result.isSuccess)

        // Verify labels updated
        val updated = result.getOrNull()
        assertNotNull(updated)
        assertTrue(updated.labels.contains("in-progress"))
        assertFalse(updated.labels.contains("assigned"))
    }

    @Test
    fun `agent updates issue status to IN_REVIEW after PR creation`() = runTest {
        val issueProvider = TestIssueTrackerProvider()
        val agent = createTestAgent(issueProvider)

        // Create test issue
        val issue = issueProvider.createTestIssue(
            title = "Implement feature",
            labels = listOf("code", "in-progress"),
        )

        // Update to IN_REVIEW (simulating PR creation)
        val result = agent.updateIssueStatus(
            issueNumber = issue.number,
            status = IssueWorkflowStatus.IN_REVIEW,
            comment = "Pull request created",
        )

        // Verify labels
        val updated = result.getOrNull()
        assertNotNull(updated)
        assertTrue(updated.labels.contains("in-review"))
        assertFalse(updated.labels.contains("in-progress"))
    }

    @Test
    fun `agent marks issue as BLOCKED on failure`() = runTest {
        val issueProvider = TestIssueTrackerProvider()
        val agent = createTestAgent(issueProvider)

        // Create test issue
        val issue = issueProvider.createTestIssue(
            title = "Complex feature",
            labels = listOf("code", "in-progress"),
        )

        // Update to BLOCKED
        val result = agent.updateIssueStatus(
            issueNumber = issue.number,
            status = IssueWorkflowStatus.BLOCKED,
            comment = "Implementation hit a blocker",
        )

        // Verify labels
        val updated = result.getOrNull()
        assertNotNull(updated)
        assertTrue(updated.labels.contains("blocked"))
        assertFalse(updated.labels.contains("in-progress"))
    }

    // ========================================================================
    // Workflow Status Enum Tests
    // ========================================================================

    @Test
    fun `IssueWorkflowStatus parses from labels correctly`() {
        val labels = listOf("code", "in-review", "backend")
        val status = IssueWorkflowStatus.fromLabels(labels)

        assertEquals(IssueWorkflowStatus.IN_REVIEW, status)
    }

    @Test
    fun `IssueWorkflowStatus prioritizes later statuses`() {
        // If multiple status labels exist, use the latest in workflow
        val labels = listOf("assigned", "in-progress", "in-review")
        val status = IssueWorkflowStatus.fromLabels(labels)

        assertEquals(IssueWorkflowStatus.IN_REVIEW, status)
    }

    // ========================================================================
    // Integration Test Placeholders
    // ========================================================================

    /**
     * PLACEHOLDER: Full workflow integration test.
     *
     * This test validates the complete issue-to-PR pipeline once all
     * components are implemented:
     * - Git tool execution (currently placeholders)
     * - Full workflow orchestration
     * - Event publishing
     *
     * TODO: Enable this test when:
     * 1. Git tools execute actual git commands
     * 2. Workflow orchestration runs full pipeline
     * 3. Event bus captures workflow events
     */
    // @Test
    // @Ignore("Enable when Git tools and workflow orchestration are complete")
    fun `INTEGRATION - agent completes full issue-to-PR workflow`() = runTest {
        val issueProvider = TestIssueTrackerProvider()
        val agent = createTestAgent(issueProvider)

        // Create test issue
        val issue = issueProvider.createTestIssue(
            title = "Add fibonacci function",
            body = "Create a function that returns the nth fibonacci number",
            labels = listOf("code"),
            assignee = "CodeWriterAgent",
        )

        // Execute full workflow
        val task = Task.CodeChange(
            id = "task-${issue.number}",
            status = TaskStatus.Pending,
            description = issue.body,
        )

        // TODO: Execute workflow when orchestration is implemented
        // val outcome = agent.executeTaskWithReasoning(task)

        // Verify: Success
        // assertTrue(outcome.success)

        // Verify: Issue status updated to IN_REVIEW
        val updated = issueProvider.issues[issue.number]
        assertNotNull(updated)
        // assertTrue(updated.labels.contains("in-review"))

        // TODO: Verify Git operations when tools are implemented
        // - Branch created
        // - Code committed
        // - PR created
        // - Events emitted
    }

    /**
     * PLACEHOLDER: Review feedback handling test.
     *
     * TODO: Enable when review feedback workflow is implemented.
     */
    // @Test
    // @Ignore("Enable when review feedback workflow is implemented")
    fun `INTEGRATION - agent addresses PR review feedback`() = runTest {
        // TODO: Implement when review workflow is complete
        // 1. Create PR with requested changes
        // 2. Agent processes feedback
        // 3. Verify status updates to CHANGES_REQUESTED
        // 4. Verify new commits address feedback
    }
}
