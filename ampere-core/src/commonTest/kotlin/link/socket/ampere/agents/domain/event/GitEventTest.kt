package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.tools.git.CreatedBranch
import link.socket.ampere.agents.execution.tools.git.CreatedCommit
import link.socket.ampere.agents.execution.tools.git.GitOperationResponse
import link.socket.ampere.agents.execution.tools.git.PushResult
import link.socket.ampere.agents.execution.tools.git.StagedFilesResult

class GitEventTest {

    @Test
    fun `GitEvent BranchCreated has correct type and summary`() {
        val event = GitEvent.BranchCreated(
            eventId = generateUUID(),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("test-agent"),
            urgency = Urgency.LOW,
            branchName = "feature/test-branch",
            baseBranch = "main",
            commitSha = "abc123def456",
            issueNumber = 42,
        )

        assertEquals("GitBranchCreated", event.eventType)
        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { when (it) { is EventSource.Agent -> it.agentId; else -> "Unknown" } },
        )
        assertTrue(summary.contains("feature/test-branch"))
        assertTrue(summary.contains("main"))
        assertTrue(summary.contains("issue #42"))
    }

    @Test
    fun `GitEvent Committed has correct type and summary`() {
        val event = GitEvent.Committed(
            eventId = generateUUID(),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("test-agent"),
            urgency = Urgency.LOW,
            commitSha = "abc123",
            message = "Fix authentication bug",
            filesCommitted = listOf("src/Auth.kt", "src/Login.kt"),
            issueNumber = 99,
        )

        assertEquals("GitCommitted", event.eventType)
        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { when (it) { is EventSource.Agent -> it.agentId; else -> "Unknown" } },
        )
        assertTrue(summary.contains("abc123"))
        assertTrue(summary.contains("Fix authentication bug"))
        assertTrue(summary.contains("2 file(s)"))
        assertTrue(summary.contains("#99"))
    }

    @Test
    fun `GitEvent PullRequestCreated has correct type and summary`() {
        val event = GitEvent.PullRequestCreated(
            eventId = generateUUID(),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("test-agent"),
            urgency = Urgency.MEDIUM,
            prNumber = 123,
            url = "https://github.com/test/repo/pull/123",
            title = "Add user authentication",
            headBranch = "feature/auth",
            baseBranch = "main",
            issueNumber = 100,
            reviewers = listOf("reviewer1", "reviewer2"),
            draft = false,
        )

        assertEquals("GitPullRequestCreated", event.eventType)
        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { when (it) { is EventSource.Agent -> it.agentId; else -> "Unknown" } },
        )
        assertTrue(summary.contains("PR #123"))
        assertTrue(summary.contains("Add user authentication"))
        assertTrue(summary.contains("closes #100"))
        assertTrue(summary.contains("reviewer1, reviewer2"))
    }

    @Test
    fun `GitEvent OperationFailed has correct type and summary`() {
        val event = GitEvent.OperationFailed(
            eventId = generateUUID(),
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("test-agent"),
            urgency = Urgency.HIGH,
            operation = "commit",
            errorMessage = "nothing to commit, working tree clean",
            isRetryable = false,
        )

        assertEquals("GitOperationFailed", event.eventType)
        val summary = event.getSummary(
            formatUrgency = { "[${it.name}]" },
            formatSource = { when (it) { is EventSource.Agent -> it.agentId; else -> "Unknown" } },
        )
        assertTrue(summary.contains("commit failed"))
        assertTrue(summary.contains("nothing to commit"))
    }

    @Test
    fun `createGitEventsFromOutcome creates BranchCreated event`() {
        val outcome = ExecutionOutcome.GitOperation.Success(
            executorId = "executor-1",
            ticketId = "ticket-1",
            taskId = "task-1",
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now(),
            response = GitOperationResponse(
                success = true,
                createdBranch = CreatedBranch(
                    branchName = "feature/new-feature",
                    baseBranch = "main",
                    commitSha = "abc123",
                ),
            ),
        )

        val events = createGitEventsFromOutcome(outcome, "agent-1")

        assertEquals(1, events.size)
        assertTrue(events[0] is GitEvent.BranchCreated)
        assertEquals("feature/new-feature", (events[0] as GitEvent.BranchCreated).branchName)
    }

    @Test
    fun `createGitEventsFromOutcome creates Committed event`() {
        val outcome = ExecutionOutcome.GitOperation.Success(
            executorId = "executor-1",
            ticketId = "ticket-1",
            taskId = "task-1",
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now(),
            response = GitOperationResponse(
                success = true,
                createdCommit = CreatedCommit(
                    commitSha = "abc123",
                    message = "Fix bug",
                    filesCommitted = listOf("file.kt"),
                ),
            ),
        )

        val events = createGitEventsFromOutcome(outcome, "agent-1")

        assertEquals(1, events.size)
        assertTrue(events[0] is GitEvent.Committed)
        assertEquals("abc123", (events[0] as GitEvent.Committed).commitSha)
    }

    @Test
    fun `createGitEventsFromOutcome creates multiple events for complex operations`() {
        val outcome = ExecutionOutcome.GitOperation.Success(
            executorId = "executor-1",
            ticketId = "ticket-1",
            taskId = "task-1",
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now(),
            response = GitOperationResponse(
                success = true,
                stagedFiles = StagedFilesResult(listOf("file1.kt", "file2.kt")),
                createdCommit = CreatedCommit(
                    commitSha = "abc123",
                    message = "Add feature",
                    filesCommitted = listOf("file1.kt", "file2.kt"),
                ),
                pushResult = PushResult(
                    branchName = "feature/test",
                    remoteName = "origin",
                    upstreamSet = true,
                ),
            ),
        )

        val events = createGitEventsFromOutcome(outcome, "agent-1")

        assertEquals(3, events.size)
        assertTrue(events.any { it is GitEvent.FilesStaged })
        assertTrue(events.any { it is GitEvent.Committed })
        assertTrue(events.any { it is GitEvent.Pushed })
    }

    @Test
    fun `createGitFailureEvent creates OperationFailed event`() {
        val outcome = ExecutionOutcome.GitOperation.Failure(
            executorId = "executor-1",
            ticketId = "ticket-1",
            taskId = "task-1",
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.UNEXPECTED,
                message = "Failed to push: rejected",
                details = "error details",
                isRetryable = true,
            ),
        )

        val event = createGitFailureEvent(outcome, "agent-1", "push")

        assertNotNull(event)
        assertEquals("push", event.operation)
        assertEquals("Failed to push: rejected", event.errorMessage)
        assertTrue(event.isRetryable)
    }
}
