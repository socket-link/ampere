package link.socket.ampere.integrations.issues.github

import kotlinx.coroutines.runBlocking
import link.socket.ampere.integrations.issues.IssueQuery
import link.socket.ampere.integrations.issues.IssueState

/**
 * Manual validation script for GitHubCliProvider.
 *
 * This is NOT a test - it's a script for manual validation.
 * To run: Execute this main function directly in your IDE.
 *
 * Prerequisites:
 * - gh CLI must be installed and authenticated
 * - You must have write access to the target repository
 *
 * WARNING: This will create real issues in the specified repository!
 */
fun main() = runBlocking {
    val provider = GitHubCliProvider()

    println("=== GitHub CLI Provider Validation ===\n")

    // Step 1: Validate Connection
    println("1. Validating connection...")
    val connectionResult = provider.validateConnection()
    if (connectionResult.isSuccess) {
        println("   ✓ Connection validated - gh CLI is authenticated\n")
    } else {
        println("   ✗ Connection failed: ${connectionResult.exceptionOrNull()?.message}")
        println("   Please run: gh auth login\n")
        return@runBlocking
    }

    // Step 2: Query existing issues
    println("2. Querying existing issues...")
    val queryResult = provider.queryIssues(
        repository = "socket-link/ampere",
        query = IssueQuery(
            state = IssueState.Open,
            limit = 5,
        ),
    )
    if (queryResult.isSuccess) {
        val issues = queryResult.getOrThrow()
        println("   ✓ Found ${issues.size} open issues")
        issues.take(3).forEach { issue ->
            println("     - #${issue.number}: ${issue.title}")
        }
        println()
    } else {
        println("   ✗ Query failed: ${queryResult.exceptionOrNull()?.message}\n")
    }

    // Step 3: Create a test issue (COMMENTED OUT - uncomment to actually create)
    /*
    println("3. Creating test issue...")
    val createRequest = IssueCreateRequest(
        localId = "manual-test",
        type = IssueType.Task,
        title = "[TEST] Manual validation of GitHubCliProvider",
        body = """
            This issue was created by the manual validation script for GitHubCliProvider.

            It can be safely closed.

            Created at: ${kotlinx.datetime.Clock.System.now()}
        """.trimIndent(),
        labels = listOf("test", "automation"),
        assignees = emptyList(),
        parent = null,
        dependsOn = emptyList()
    )

    val createResult = provider.createIssue(
        repository = "socket-link/ampere",
        request = createRequest,
        resolvedDependencies = emptyMap()
    )

    if (createResult.isSuccess) {
        val created = createResult.getOrThrow()
        println("   ✓ Created issue #${created.issueNumber}")
        println("     URL: ${created.url}\n")

        // Step 4: Update the issue
        println("4. Updating test issue...")
        val updateResult = provider.updateIssue(
            repository = "socket-link/ampere",
            issueNumber = created.issueNumber,
            update = IssueUpdate(
                body = "Updated body - this issue can be closed"
            )
        )

        if (updateResult.isSuccess) {
            println("   ✓ Updated issue #${created.issueNumber}\n")

            // Step 5: Close the issue
            println("5. Closing test issue...")
            val closeResult = provider.updateIssue(
                repository = "socket-link/ampere",
                issueNumber = created.issueNumber,
                update = IssueUpdate(state = IssueState.Closed)
            )

            if (closeResult.isSuccess) {
                println("   ✓ Closed issue #${created.issueNumber}\n")
            } else {
                println("   ✗ Close failed: ${closeResult.exceptionOrNull()?.message}\n")
            }
        } else {
            println("   ✗ Update failed: ${updateResult.exceptionOrNull()?.message}\n")
        }
    } else {
        println("   ✗ Create failed: ${createResult.exceptionOrNull()?.message}\n")
    }
     */

    println("=== Validation Complete ===")
    println("\nNote: Issue creation is commented out to prevent accidental issue spam.")
    println("Uncomment the section in the script if you want to test actual issue creation.")
}
