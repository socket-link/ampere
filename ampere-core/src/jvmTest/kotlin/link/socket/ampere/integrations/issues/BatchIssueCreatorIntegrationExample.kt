package link.socket.ampere.integrations.issues

import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest
import link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest
import link.socket.ampere.agents.execution.tools.issue.IssueType
import link.socket.ampere.integrations.issues.github.GitHubCliProvider

/**
 * Example demonstrating batch issue creation with dependency resolution.
 *
 * This is NOT a test - it's an example showing how to use BatchIssueCreator
 * with GitHubCliProvider to create hierarchical issues with dependencies.
 *
 * To run: Execute this main function directly in your IDE.
 *
 * Prerequisites:
 * - gh CLI must be installed and authenticated
 * - You must have write access to the target repository
 *
 * WARNING: This will create real issues in the specified repository!
 * The creation code is commented out by default.
 */
fun main() = runBlocking {
    val provider = GitHubCliProvider()
    val creator = BatchIssueCreator(provider)

    println("=== Batch Issue Creation Example ===\n")

    // Step 1: Validate connection
    println("1. Validating GitHub connection...")
    val connectionResult = provider.validateConnection()
    if (connectionResult.isFailure) {
        println("   ✗ Not authenticated. Run: gh auth login")
        return@runBlocking
    }
    println("   ✓ Connected to GitHub\n")

    // Step 2: Define the issue hierarchy
    println("2. Defining issue structure...")
    val batchRequest = BatchIssueCreateRequest(
        repository = "socket-link/ampere",
        issues = listOf(
            // Epic (will be created first despite being listed in the middle)
            IssueCreateRequest(
                localId = "epic-auth",
                type = IssueType.Feature,
                title = "Implement User Authentication System",
                body = """
                    # Authentication System Epic

                    Implement a complete user authentication system with:
                    - Login/logout
                    - Password reset
                    - Session management

                    This epic tracks all authentication-related work.
                """.trimIndent(),
                labels = listOf("feature", "authentication"),
            ),

            // Task 1: No dependencies (will be created second)
            IssueCreateRequest(
                localId = "task-auth-backend",
                type = IssueType.Task,
                title = "Build authentication backend API",
                body = """
                    Implement backend API endpoints for authentication:
                    - POST /auth/login
                    - POST /auth/logout
                    - POST /auth/refresh

                    Include JWT token generation and validation.
                """.trimIndent(),
                labels = listOf("task", "backend", "authentication"),
                parent = "epic-auth",
            ),

            // Task 2: Depends on task 1 (will be created third)
            IssueCreateRequest(
                localId = "task-auth-frontend",
                type = IssueType.Task,
                title = "Build authentication UI components",
                body = """
                    Create frontend components for authentication:
                    - Login form
                    - Logout button
                    - Session indicator

                    Must integrate with backend API.
                """.trimIndent(),
                labels = listOf("task", "frontend", "authentication"),
                parent = "epic-auth",
                dependsOn = listOf("task-auth-backend"),
            ),

            // Task 3: Depends on both previous tasks (will be created last)
            IssueCreateRequest(
                localId = "task-auth-tests",
                type = IssueType.Task,
                title = "Write end-to-end authentication tests",
                body = """
                    Create comprehensive tests covering:
                    - Login flow
                    - Logout flow
                    - Session expiration
                    - Invalid credentials

                    Requires both backend and frontend to be complete.
                """.trimIndent(),
                labels = listOf("task", "testing", "authentication"),
                parent = "epic-auth",
                dependsOn = listOf("task-auth-backend", "task-auth-frontend"),
            ),
        ),
    )

    println("   Defined 4 issues:")
    println("     - 1 epic (authentication)")
    println("     - 3 tasks (backend, frontend, tests)")
    println("   Dependencies:")
    println("     - Frontend depends on backend")
    println("     - Tests depend on backend and frontend\n")

    // Step 3: Show creation order
    println("3. Issue creation order will be:")
    println("   1. Epic (parent of all)")
    println("   2. Backend task (no dependencies)")
    println("   3. Frontend task (depends on backend)")
    println("   4. Tests task (depends on backend and frontend)\n")

    // Step 4: Create issues (COMMENTED OUT - uncomment to actually create)
    /*
    println("4. Creating issues...")
    val response = creator.createBatch(batchRequest)

    if (response.success) {
        println("   ✓ Successfully created ${response.created.size} issues\n")

        println("5. Created issues:")
        response.created.forEach { created ->
            println("   - #${created.issueNumber}: ${created.localId}")
            println("     ${created.url}")
        }
        println()

        println("6. Dependency resolution:")
        val epic = response.created.find { it.localId == "epic-auth" }
        val backend = response.created.find { it.localId == "task-auth-backend" }
        val frontend = response.created.find { it.localId == "task-auth-frontend" }
        val tests = response.created.find { it.localId == "task-auth-tests" }

        println("   Epic #${epic?.issueNumber}")
        println("   ↓")
        println("   Backend #${backend?.issueNumber} (parent: #${backend?.parentIssueNumber})")
        println("   ↓")
        println("   Frontend #${frontend?.issueNumber} (parent: #${frontend?.parentIssueNumber})")
        println("   ↓")
        println("   Tests #${tests?.issueNumber} (parent: #${tests?.parentIssueNumber})")
        println()

        println("Note: Check the issue bodies to see dependency references like 'Depends on #N'")
    } else {
        println("   ✗ Some issues failed to create:")
        response.errors.forEach { error ->
            println("   - ${error.localId}: ${error.message}")
        }
        println()
        println("   Successfully created ${response.created.size} issues")
    }
     */

    println("=== Example Complete ===")
    println("\nNote: Issue creation is commented out to prevent accidental issue spam.")
    println("Uncomment the section in the script if you want to test actual batch creation.")
}
