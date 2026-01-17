package link.socket.ampere.agents.definition.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.ampere.integrations.issues.ExistingIssue
import link.socket.ampere.integrations.issues.IssueState

/**
 * Tests for CodeAgentGitHelpers - Git workflow helper functions.
 */
class CodeAgentGitHelpersTest {

    // ========================================================================
    // Branch Name Generation Tests
    // ========================================================================

    @Test
    fun `generateBranchName creates correct format`() {
        val issue = ExistingIssue(
            number = 123,
            title = "Add user authentication",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/123",
        )

        val branchName = CodeAgentGitHelpers.generateBranchName(issue)

        assertEquals("feature/123-add-user-authentication", branchName)
    }

    @Test
    fun `generateBranchName handles special characters`() {
        val issue = ExistingIssue(
            number = 456,
            title = "Fix: Database Connection Timeout!!! (Critical)",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/456",
        )

        val branchName = CodeAgentGitHelpers.generateBranchName(issue)

        // Should convert special characters to hyphens and truncate to fit max length
        assertTrue(branchName.startsWith("feature/456-"))
        assertTrue(branchName.contains("fix"))
        assertTrue(branchName.contains("database"))
        assertTrue(branchName.contains("timeout"))
        assertTrue(branchName.length <= 60) // Reasonable max length
    }

    @Test
    fun `generateBranchName truncates long titles`() {
        val issue = ExistingIssue(
            number = 789,
            title = "Implement comprehensive authentication system with OAuth2 and JWT tokens including refresh token rotation",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/789",
        )

        val branchName = CodeAgentGitHelpers.generateBranchName(issue)

        assertTrue(branchName.startsWith("feature/789-"))
        assertTrue(branchName.length <= 50) // feature/ + 3 digits + - + 40 chars
    }

    @Test
    fun `generateBranchName with custom prefix`() {
        val issue = ExistingIssue(
            number = 100,
            title = "Fix critical security vulnerability",
            body = "",
            state = IssueState.Open,
            labels = listOf("hotfix"),
            url = "https://github.com/test/repo/issues/100",
        )

        val branchName = CodeAgentGitHelpers.generateBranchName(issue, prefix = "hotfix")

        assertEquals("hotfix/100-fix-critical-security-vulnerability", branchName)
    }

    // ========================================================================
    // Commit Message Generation Tests
    // ========================================================================

    @Test
    fun `generateCommitMessage with feat label`() {
        val issue = ExistingIssue(
            number = 123,
            title = "Add user authentication",
            body = "",
            state = IssueState.Open,
            labels = listOf("feature"),
            url = "https://github.com/test/repo/issues/123",
        )

        val message = CodeAgentGitHelpers.generateCommitMessage(issue)

        assertTrue(message.startsWith("feat"))
        assertTrue(message.contains("add user authentication"))
    }

    @Test
    fun `generateCommitMessage with bug label`() {
        val issue = ExistingIssue(
            number = 456,
            title = "Fix database connection timeout",
            body = "",
            state = IssueState.Open,
            labels = listOf("bug"),
            url = "https://github.com/test/repo/issues/456",
        )

        val message = CodeAgentGitHelpers.generateCommitMessage(issue)

        assertTrue(message.startsWith("fix"))
        assertTrue(message.contains("database connection timeout"))
    }

    @Test
    fun `generateCommitMessage with scope from changed files`() {
        val issue = ExistingIssue(
            number = 789,
            title = "Add user login endpoint",
            body = "",
            state = IssueState.Open,
            labels = listOf("feature"),
            url = "https://github.com/test/repo/issues/789",
        )

        val files = listOf(
            "src/main/kotlin/auth/LoginController.kt",
            "src/main/kotlin/auth/AuthService.kt",
        )

        val message = CodeAgentGitHelpers.generateCommitMessage(issue, files)

        assertTrue(message.startsWith("feat("))
        assertTrue(message.contains("auth") || message.contains("main"))
    }

    @Test
    fun `generateCommitMessage strips type prefix from title`() {
        val issue = ExistingIssue(
            number = 111,
            title = "Feat: Implement dark mode",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/111",
        )

        val message = CodeAgentGitHelpers.generateCommitMessage(issue)

        assertTrue(message.startsWith("feat:"))
        assertTrue(message.contains("implement dark mode"))
        assertTrue(!message.contains("feat: feat:")) // No double prefix
    }

    @Test
    fun `generateCommitMessage for test files`() {
        val issue = ExistingIssue(
            number = 222,
            title = "Add unit tests for auth service",
            body = "",
            state = IssueState.Open,
            labels = listOf("test"),
            url = "https://github.com/test/repo/issues/222",
        )

        val files = listOf(
            "src/test/kotlin/auth/AuthServiceTest.kt",
            "src/test/kotlin/auth/LoginControllerTest.kt",
        )

        val message = CodeAgentGitHelpers.generateCommitMessage(issue, files)

        assertTrue(message.startsWith("test"))
    }

    @Test
    fun `generateCommitMessage for documentation`() {
        val issue = ExistingIssue(
            number = 333,
            title = "Update API documentation",
            body = "",
            state = IssueState.Open,
            labels = listOf("documentation"),
            url = "https://github.com/test/repo/issues/333",
        )

        val files = listOf(
            "docs/API.md",
            "README.md",
        )

        val message = CodeAgentGitHelpers.generateCommitMessage(issue, files)

        assertTrue(message.startsWith("docs"))
    }

    // ========================================================================
    // PR Title Generation Tests
    // ========================================================================

    @Test
    fun `generatePRTitle uses issue title`() {
        val issue = ExistingIssue(
            number = 123,
            title = "Add user authentication",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/123",
        )

        val prTitle = CodeAgentGitHelpers.generatePRTitle(issue)

        assertEquals("Add user authentication", prTitle)
    }

    // ========================================================================
    // PR Body Generation Tests
    // ========================================================================

    @Test
    fun `generatePRBody includes summary from issue body`() {
        val issue = ExistingIssue(
            number = 123,
            title = "Add user authentication",
            body = "We need to implement OAuth2 authentication for users.\nThis will allow secure login.",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/123",
        )

        val prBody = CodeAgentGitHelpers.generatePRBody(issue)

        assertTrue(prBody.contains("## Summary"))
        assertTrue(prBody.contains("OAuth2 authentication"))
        assertTrue(prBody.contains("Closes #123"))
    }

    @Test
    fun `generatePRBody includes changed files`() {
        val issue = ExistingIssue(
            number = 456,
            title = "Fix database connection",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/456",
        )

        val files = listOf(
            "src/main/kotlin/database/Connection.kt",
            "src/test/kotlin/database/ConnectionTest.kt",
        )

        val prBody = CodeAgentGitHelpers.generatePRBody(issue, files)

        assertTrue(prBody.contains("## Changes"))
        assertTrue(prBody.contains("Source Code"))
        assertTrue(prBody.contains("Tests"))
        assertTrue(prBody.contains("Connection.kt"))
        assertTrue(prBody.contains("ConnectionTest.kt"))
    }

    @Test
    fun `generatePRBody includes testing checklist`() {
        val issue = ExistingIssue(
            number = 789,
            title = "Add new feature",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/789",
        )

        val prBody = CodeAgentGitHelpers.generatePRBody(issue)

        assertTrue(prBody.contains("## Testing"))
        assertTrue(prBody.contains("Code compiles without errors"))
        assertTrue(prBody.contains("All existing tests pass"))
        assertTrue(prBody.contains("Manual testing completed"))
    }

    @Test
    fun `generatePRBody adds test checklist when tests included`() {
        val issue = ExistingIssue(
            number = 999,
            title = "Add feature with tests",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/999",
        )

        val files = listOf(
            "src/main/kotlin/Feature.kt",
            "src/test/kotlin/FeatureTest.kt",
        )

        val prBody = CodeAgentGitHelpers.generatePRBody(issue, files)

        assertTrue(prBody.contains("New tests added and passing"))
    }

    @Test
    fun `generatePRBody truncates long file lists`() {
        val issue = ExistingIssue(
            number = 888,
            title = "Refactor codebase",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/888",
        )

        val files = (1..15).map { "src/main/kotlin/File$it.kt" }

        val prBody = CodeAgentGitHelpers.generatePRBody(issue, files)

        assertTrue(prBody.contains("and 5 more"))
    }

    // ========================================================================
    // Reviewer Generation Tests
    // ========================================================================

    @Test
    fun `generateReviewers includes QA by default`() {
        val issue = ExistingIssue(
            number = 123,
            title = "Add feature",
            body = "",
            state = IssueState.Open,
            labels = emptyList(),
            url = "https://github.com/test/repo/issues/123",
        )

        val reviewers = CodeAgentGitHelpers.generateReviewers(issue)

        assertTrue(reviewers.contains("QATestingAgent"))
    }

    @Test
    fun `generateReviewers adds security agent for security issues`() {
        val issue = ExistingIssue(
            number = 456,
            title = "Fix security vulnerability",
            body = "",
            state = IssueState.Open,
            labels = listOf("security", "critical"),
            url = "https://github.com/test/repo/issues/456",
        )

        val reviewers = CodeAgentGitHelpers.generateReviewers(issue)

        assertTrue(reviewers.contains("QATestingAgent"))
        assertTrue(reviewers.contains("SecurityReviewAgent"))
    }

    @Test
    fun `generateReviewers adds performance agent for performance issues`() {
        val issue = ExistingIssue(
            number = 789,
            title = "Optimize database queries",
            body = "",
            state = IssueState.Open,
            labels = listOf("performance"),
            url = "https://github.com/test/repo/issues/789",
        )

        val reviewers = CodeAgentGitHelpers.generateReviewers(issue)

        assertTrue(reviewers.contains("QATestingAgent"))
        assertTrue(reviewers.contains("PerformanceOptimizationAgent"))
    }

    @Test
    fun `generateReviewers avoids duplicates`() {
        val issue = ExistingIssue(
            number = 999,
            title = "Security and performance fix",
            body = "",
            state = IssueState.Open,
            labels = listOf("security", "performance", "security"),
            url = "https://github.com/test/repo/issues/999",
        )

        val reviewers = CodeAgentGitHelpers.generateReviewers(issue)

        assertEquals(reviewers.size, reviewers.distinct().size, "Should not have duplicate reviewers")
    }
}
