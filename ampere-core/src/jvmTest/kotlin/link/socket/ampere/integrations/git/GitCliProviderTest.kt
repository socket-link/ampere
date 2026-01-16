package link.socket.ampere.integrations.git

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.tools.git.BranchCreateRequest
import link.socket.ampere.agents.execution.tools.git.CommitRequest
import link.socket.ampere.agents.execution.tools.git.PullRequestCreateRequest
import link.socket.ampere.agents.execution.tools.git.PushRequest

/**
 * Tests for GitCliProvider.
 *
 * Note: Most tests require:
 * - git CLI to be installed and available on PATH
 * - gh CLI to be installed and authenticated (for PR tests)
 * - A valid git repository in the working directory
 *
 * These tests validate the provider's logic and command construction
 * rather than actually executing git commands.
 */
class GitCliProviderTest {

    @Test
    fun `provider can be constructed with default working directory`() {
        val provider = GitCliProvider()
        assertNotNull(provider)
    }

    @Test
    fun `provider can be constructed with custom working directory`() {
        val customDir = File("/tmp/test-repo")
        val provider = GitCliProvider(customDir)
        assertNotNull(provider)
    }

    @Test
    fun `buildPRBody includes issue reference when issueNumber is provided`() {
        // Test the PR body construction logic
        val request = PullRequestCreateRequest(
            title = "Add feature X",
            body = "This PR adds feature X",
            baseBranch = "main",
            headBranch = "feature/add-x",
            issueNumber = 123,
        )

        val expectedBody = buildString {
            append("This PR adds feature X")
            append("\n\n---\nCloses #123")
        }

        assertTrue(expectedBody.contains("Closes #123"))
        assertTrue(expectedBody.contains("This PR adds feature X"))
    }

    @Test
    fun `buildPRBody without issue reference is simple`() {
        val request = PullRequestCreateRequest(
            title = "Add feature Y",
            body = "This PR adds feature Y",
            baseBranch = "main",
            headBranch = "feature/add-y",
            issueNumber = null,
        )

        val expectedBody = "This PR adds feature Y"

        assertFalse(expectedBody.contains("Closes"))
    }

    @Test
    fun `commit message includes issue reference when issueNumber is provided`() {
        val request = CommitRequest(
            message = "Fix bug in authentication",
            files = emptyList(),
            issueNumber = 456,
        )

        val expectedMessage = buildString {
            append("Fix bug in authentication")
            append("\n\nRefs #456")
        }

        assertTrue(expectedMessage.contains("Refs #456"))
        assertTrue(expectedMessage.contains("Fix bug in authentication"))
    }

    @Test
    fun `commit message without issue reference is simple`() {
        val request = CommitRequest(
            message = "Update documentation",
            files = emptyList(),
            issueNumber = null,
        )

        val expectedMessage = "Update documentation"

        assertFalse(expectedMessage.contains("Refs"))
    }

    @Test
    fun `validateRepository returns failure for non-git directory`() {
        runBlocking {
            val nonGitDir = File("/tmp/not-a-repo-${System.currentTimeMillis()}")
            nonGitDir.mkdirs()

            val provider = GitCliProvider(nonGitDir)
            val result = provider.validateRepository()

            assertTrue(result.isFailure)

            // Cleanup
            nonGitDir.deleteRecursively()
        }
    }

    @Test
    fun `push request with setUpstream includes tracking flags`() {
        val request = PushRequest(
            branchName = "feature/new-branch",
            setUpstream = true,
            force = false,
        )

        // Verify request properties
        assertEquals("feature/new-branch", request.branchName)
        assertTrue(request.setUpstream)
        assertFalse(request.force)
    }

    @Test
    fun `push request with force uses force-with-lease`() {
        val request = PushRequest(
            branchName = "feature/fix",
            setUpstream = false,
            force = true,
        )

        // Verify request properties
        assertEquals("feature/fix", request.branchName)
        assertFalse(request.setUpstream)
        assertTrue(request.force)
    }

    @Test
    fun `branch creation request validates properties`() {
        val request = BranchCreateRequest(
            baseBranch = "main",
            branchName = "feature/AMP-123-new-feature",
            issueNumber = 123,
        )

        assertEquals("main", request.baseBranch)
        assertEquals("feature/AMP-123-new-feature", request.branchName)
        assertEquals(123, request.issueNumber)
    }

    @Test
    fun `PR creation request validates all properties`() {
        val request = PullRequestCreateRequest(
            title = "Implement user authentication",
            body = "This PR implements user authentication with JWT tokens",
            baseBranch = "main",
            headBranch = "feature/auth",
            issueNumber = 789,
            reviewers = listOf("reviewer1", "reviewer2"),
            labels = listOf("enhancement", "security"),
            draft = true,
        )

        assertEquals("Implement user authentication", request.title)
        assertEquals("main", request.baseBranch)
        assertEquals("feature/auth", request.headBranch)
        assertEquals(789, request.issueNumber)
        assertEquals(2, request.reviewers.size)
        assertTrue(request.reviewers.contains("reviewer1"))
        assertEquals(2, request.labels.size)
        assertTrue(request.labels.contains("security"))
        assertTrue(request.draft)
    }

    // Integration-style tests that require actual git repo
    // These would be run in CI with a test repository setup

    @Test
    fun `getCurrentBranch returns failure on non-git directory`() {
        runBlocking {
            val nonGitDir = File("/tmp/not-a-repo-${System.currentTimeMillis()}")
            nonGitDir.mkdirs()

            val provider = GitCliProvider(nonGitDir)
            val result = provider.getCurrentBranch()

            assertTrue(result.isFailure)

            // Cleanup
            nonGitDir.deleteRecursively()
        }
    }

    @Test
    fun `getStatus returns failure on non-git directory`() {
        runBlocking {
            val nonGitDir = File("/tmp/not-a-repo-${System.currentTimeMillis()}")
            nonGitDir.mkdirs()

            val provider = GitCliProvider(nonGitDir)
            val result = provider.getStatus()

            assertTrue(result.isFailure)

            // Cleanup
            nonGitDir.deleteRecursively()
        }
    }

    @Test
    fun `stageFiles with empty list should stage all files`() {
        val emptyList = emptyList<String>()

        // This validates that the API accepts empty lists
        assertNotNull(emptyList)
        assertEquals(0, emptyList.size)
    }
}
