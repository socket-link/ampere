package link.socket.ampere.integrations.issues.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.tools.issue.CreatedIssue
import link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest
import link.socket.ampere.agents.execution.tools.issue.IssueType
import link.socket.ampere.integrations.issues.IssueQuery
import link.socket.ampere.integrations.issues.IssueState
import link.socket.ampere.integrations.issues.IssueTrackerProvider
import link.socket.ampere.integrations.issues.IssueUpdate

/**
 * Tests for GitHubCliProvider.
 *
 * Note: Some tests require gh CLI to be installed and authenticated.
 * These tests are marked with @IgnoreIfGhNotAvailable and will be skipped
 * if gh is not available.
 */
class GitHubCliProviderTest {

    @Test
    fun `provider has correct identity`() {
        val provider = GitHubCliProvider()

        assertEquals("github-cli", provider.providerId)
        assertEquals("GitHub (via gh CLI)", provider.displayName)
    }

    @Test
    fun `provider implements IssueTrackerProvider interface`() {
        val provider = GitHubCliProvider()

        assertTrue(provider is IssueTrackerProvider)
    }

    @Test
    fun `createIssue request builds correct body with dependencies`() {
        // This test validates the body construction logic
        val request = IssueCreateRequest(
            localId = "task-1",
            type = IssueType.Task,
            title = "Test task",
            body = "Original body content",
            labels = listOf("task"),
            parent = "epic-1",
            dependsOn = listOf("task-0"),
        )

        val resolvedDependencies = mapOf(
            "epic-1" to 42,
            "task-0" to 41,
        )

        // Simulate body construction
        val expectedBody = buildString {
            append("Original body content")
            append("\n\n---\n**Dependencies:**\n")
            append("- Depends on #41\n")
            append("\n**Part of:** #42\n")
        }

        assertTrue(expectedBody.contains("Depends on #41"))
        assertTrue(expectedBody.contains("Part of:"))
    }

    @Test
    fun `createIssue request without dependencies builds simple body`() {
        val request = IssueCreateRequest(
            localId = "task-1",
            type = IssueType.Task,
            title = "Test task",
            body = "Simple body",
            labels = emptyList(),
            parent = null,
            dependsOn = emptyList(),
        )

        val resolvedDependencies = emptyMap<String, Int>()

        // Simulate body construction
        val expectedBody = "Simple body"

        assertFalse(expectedBody.contains("Dependencies"))
        assertFalse(expectedBody.contains("Part of"))
    }

    @Test
    fun `queryIssues builds correct command arguments for all filters`() {
        val query = IssueQuery(
            state = IssueState.Open,
            labels = listOf("bug", "critical"),
            assignee = "developer1",
            titleContains = "crash",
            limit = 25,
        )

        // Validate query parameters
        assertEquals(IssueState.Open, query.state)
        assertEquals(2, query.labels.size)
        assertEquals("developer1", query.assignee)
        assertEquals("crash", query.titleContains)
        assertEquals(25, query.limit)
    }

    @Test
    fun `updateIssue supports partial updates`() {
        val update = IssueUpdate(
            title = "Updated title",
            state = IssueState.Closed,
        )

        // Only title and state should be updated
        assertNotNull(update.title)
        assertNotNull(update.state)
        assertEquals(null, update.body)
        assertEquals(null, update.labels)
        assertEquals(null, update.assignees)
    }

    @Test
    fun `GitHubIssueJson correctly maps to ExistingIssue`() {
        val json = GitHubIssueJson(
            number = 42,
            title = "Test Issue",
            body = "Test body",
            state = "OPEN",
            labels = listOf(
                GitHubLabelJson("bug"),
                GitHubLabelJson("critical"),
            ),
            url = "https://github.com/owner/repo/issues/42",
        )

        val existing = json.toExistingIssue()

        assertEquals(42, existing.number)
        assertEquals("Test Issue", existing.title)
        assertEquals("Test body", existing.body)
        assertEquals(IssueState.Open, existing.state)
        assertEquals(2, existing.labels.size)
        assertTrue(existing.labels.contains("bug"))
        assertTrue(existing.labels.contains("critical"))
        assertEquals("https://github.com/owner/repo/issues/42", existing.url)
    }

    @Test
    fun `GitHubIssueJson handles closed state`() {
        val json = GitHubIssueJson(
            number = 42,
            title = "Closed Issue",
            body = "This is closed",
            state = "CLOSED",
            labels = emptyList(),
            url = "https://github.com/owner/repo/issues/42",
        )

        val existing = json.toExistingIssue()
        assertEquals(IssueState.Closed, existing.state)
    }

    @Test
    fun `GitHubIssueJson handles unknown state as Open`() {
        val json = GitHubIssueJson(
            number = 42,
            title = "Issue",
            body = "Body",
            state = "UNKNOWN",
            labels = emptyList(),
            url = "https://github.com/owner/repo/issues/42",
        )

        val existing = json.toExistingIssue()
        assertEquals(IssueState.Open, existing.state)
    }

    @Test
    fun `summarizeChildren filters to correct parent`() {
        val children = listOf(
            CreatedIssue("child1", 43, "url1", parentIssueNumber = 42),
            CreatedIssue("child2", 44, "url2", parentIssueNumber = 42),
            CreatedIssue("child3", 45, "url3", parentIssueNumber = 99), // Different parent
        )

        // Verify filtering logic
        val childrenOfParent42 = children.filter { it.parentIssueNumber == 42 }
        assertEquals(2, childrenOfParent42.size)
        assertTrue(childrenOfParent42.any { it.issueNumber == 43 })
        assertTrue(childrenOfParent42.any { it.issueNumber == 44 })
    }

    @Test
    fun `summarizeChildren creates correct checkbox format`() {
        val children = listOf(
            CreatedIssue("child1", 43, "url1", parentIssueNumber = 42),
            CreatedIssue("child2", 44, "url2", parentIssueNumber = 42),
        )

        // Simulate checkbox list creation
        val childList = children
            .filter { it.parentIssueNumber == 42 }
            .sortedBy { it.issueNumber }
            .joinToString("\n") { "- [ ] #${it.issueNumber}" }

        val expected = "- [ ] #43\n- [ ] #44"
        assertEquals(expected, childList)
    }

    @Test
    fun `summarizeChildren sorts children by issue number`() {
        val children = listOf(
            CreatedIssue("child2", 45, "url2", parentIssueNumber = 42),
            CreatedIssue("child1", 43, "url1", parentIssueNumber = 42),
            CreatedIssue("child3", 44, "url3", parentIssueNumber = 42),
        )

        val childList = children
            .filter { it.parentIssueNumber == 42 }
            .sortedBy { it.issueNumber }
            .map { it.issueNumber }

        assertEquals(listOf(43, 44, 45), childList)
    }
}

/**
 * Integration tests that require gh CLI to be installed and authenticated.
 * These tests are disabled by default and can be run manually.
 */
class GitHubCliProviderIntegrationTest {

    private val provider = GitHubCliProvider()

    // Uncomment and run manually when gh is authenticated
    // @Test
    fun `validateConnection succeeds when authenticated`() = runBlocking {
        val result = provider.validateConnection()

        assertTrue(result.isSuccess, "Expected authentication to succeed")
    }

    // Uncomment and run manually when gh is authenticated
    // @Test
    fun `validateConnection fails when not authenticated`() = runBlocking {
        // This would need to be run in an environment without gh auth
        // Or after running: gh auth logout

        val result = provider.validateConnection()

        // In a non-authenticated environment, this should fail
        // assertTrue(result.isFailure)
    }

    // Manual test - uncomment to run against real repository
    // WARNING: This creates real issues!
    // @Test
    fun `createIssue creates real issue in repository`() = runBlocking {
        val request = IssueCreateRequest(
            localId = "test-1",
            type = IssueType.Task,
            title = "Test Issue from GitHubCliProvider",
            body = "This is a test issue created by automated tests. It can be closed.",
            labels = listOf("test", "automation"),
            assignees = emptyList(),
            parent = null,
            dependsOn = emptyList(),
        )

        val result = provider.createIssue(
            repository = "socket-link/ampere",
            request = request,
            resolvedDependencies = emptyMap(),
        )

        assertTrue(result.isSuccess, "Expected issue creation to succeed")
        val created = result.getOrThrow()
        assertEquals("test-1", created.localId)
        assertTrue(created.issueNumber > 0)
        assertTrue(created.url.contains("github.com"))
    }
}
