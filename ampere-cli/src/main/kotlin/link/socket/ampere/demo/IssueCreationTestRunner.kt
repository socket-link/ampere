package link.socket.ampere.demo

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.executeCreateIssues
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest
import link.socket.ampere.agents.execution.tools.issue.IssueType

/**
 * Issue Creation Test Runner - Demonstrates end-to-end issue creation.
 *
 * This program:
 * 1. Loads test JSON with complete epic + tasks structure
 * 2. Validates the JSON structure
 * 3. Creates issues in GitHub using BatchIssueCreator
 * 4. Displays created issue numbers and URLs
 *
 * To run this:
 *   ./gradlew :ampere-cli:installJvmDist
 *   ./ampere-cli/ampere test ticket
 *
 * Prerequisites:
 * - gh CLI must be installed and authenticated (run: gh auth login)
 */
fun runIssueCreationTest() {
    println("═".repeat(80))
    println("ISSUE CREATION TEST - Issue Management Tool Validation")
    println("═".repeat(80))
    println()

    runBlocking {
        // Step 1: Display test data
        println("📋 Test Data: Issue Management Tool Epic")
        println("   This will create an epic and 7 tasks demonstrating the full workflow")
        println()

        // Step 2: Load test JSON
        val testJson = getTestIssueJson()

        println("📦 Parsing JSON...")
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val request = try {
            json.decodeFromString(BatchIssueCreateRequest.serializer(), testJson)
        } catch (e: Exception) {
            println("❌ Failed to parse JSON: ${e.message}")
            return@runBlocking
        }

        println("✅ JSON parsed successfully")
        println("   Repository: ${request.repository}")
        println("   Issues to create: ${request.issues.size}")
        println()

        // Analyze structure
        val epic = request.issues.find { it.type == IssueType.Feature }
        val tasks = request.issues.filter { it.type == IssueType.Task }

        if (epic != null) {
            println("   📌 Epic: ${epic.title}")
            tasks.forEachIndexed { index, task ->
                val deps = if (task.dependsOn.isNotEmpty()) {
                    " (depends on: ${task.dependsOn.joinToString(", ")})"
                } else {
                    ""
                }
                println("   ${index + 1}. ${task.title}$deps")
            }
            println()
        }

        // Step 3: Confirm with user
        println("─".repeat(80))
        println("⚠️  WARNING: This will create REAL issues in GitHub!")
        println("─".repeat(80))
        println()
        println("Repository: ${request.repository}")
        println("Issues: 1 epic + ${tasks.size} tasks")
        println()
        print("Do you want to proceed? [y/N]: ")

        val response = readLine()?.lowercase()
        if (response != "y" && response != "yes") {
            println()
            println("❌ Cancelled by user")
            println()
            return@runBlocking
        }

        println()
        println("─".repeat(80))
        println("CREATING ISSUES")
        println("─".repeat(80))
        println()

        // Step 4: Create execution context
        val context = ExecutionContext.IssueManagement(
            executorId = "issue-test-executor",
            ticket = Ticket(
                id = "ticket-issue-test",
                title = "Create Issue Management Tool Epic",
                description = "Validate end-to-end issue creation",
                type = TicketType.FEATURE,
                priority = TicketPriority.HIGH,
                status = TicketStatus.InProgress,
                assignedAgentId = null,
                createdByAgentId = "human-issue-test",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
            task = Task.CodeChange(
                id = "task-issue-test",
                status = TaskStatus.InProgress,
                description = "Create issues for validation",
            ),
            instructions = "Create the issue management tool epic and all subtasks",
            issueRequest = request,
        )

        // Step 5: Execute issue creation
        println("⚡ Executing issue creation...")
        println()

        val outcome = executeCreateIssues(context)

        // Step 6: Display results
        println()
        println("─".repeat(80))
        println("RESULTS")
        println("─".repeat(80))
        println()

        when (outcome) {
            is ExecutionOutcome.IssueManagement.Success -> {
                val response = outcome.response
                println("✅ SUCCESS! Created ${response.created.size} issues")
                println()

                // Group by parent for better display
                val epicIssue = response.created.find { it.parentIssueNumber == null }
                val childIssues = response.created.filter { it.parentIssueNumber != null }
                    .sortedBy { it.issueNumber }

                if (epicIssue != null) {
                    println("📌 Epic: #${epicIssue.issueNumber}")
                    println("   ${epicIssue.url}")
                    println()
                    println("   Subtasks:")
                    childIssues.forEach { child ->
                        println("   - [ ] #${child.issueNumber} (${child.localId})")
                    }
                } else {
                    println("Created issues:")
                    response.created.forEach { created ->
                        println("   #${created.issueNumber}: ${created.localId}")
                        println("   ${created.url}")
                    }
                }

                println()
                println("═".repeat(80))
                println("✅ TEST PASSED - All issues created successfully!")
                println("═".repeat(80))
                println()
                println("You can now:")
                println("  1. View the epic on GitHub: ${epicIssue?.url ?: response.created.first().url}")
                println("  2. Observe the parent-child relationships")
                println("  3. Check the dependency references in issue bodies")
                println()
            }

            is ExecutionOutcome.IssueManagement.Failure -> {
                println("❌ FAILURE: ${outcome.error.message}")
                println()

                if (outcome.error.details != null) {
                    println("Details:")
                    println(outcome.error.details)
                    println()
                }

                val partial = outcome.partialResponse
                if (partial != null) {
                    println("⚠️  Partial Success:")
                    println("   Created: ${partial.created.size} issues")
                    println("   Errors: ${partial.errors.size}")
                    println()

                    if (partial.created.isNotEmpty()) {
                        println("Successfully created:")
                        partial.created.forEach { created ->
                            println("   ✓ #${created.issueNumber}: ${created.localId}")
                        }
                        println()
                    }

                    if (partial.errors.isNotEmpty()) {
                        println("Failed to create:")
                        partial.errors.forEach { error ->
                            println("   ✗ ${error.localId}: ${error.message}")
                        }
                        println()
                    }
                }

                println("═".repeat(80))
                println("❌ TEST FAILED")
                println("═".repeat(80))
                println()
            }
        }
    }
}

/**
 * Returns the complete test JSON for issue creation.
 * This represents the Issue Management Tool epic with all 7 tasks.
 */
private fun getTestIssueJson(): String {
    return """
    {
      "repository": "socket-link/ampere",
      "issues": [
        {
          "localId": "issue-tool-epic",
          "type": "Feature",
          "title": "Issue Management Tool for ProjectManagerAgent",
          "body": "## Context\n\nAMPERE agents need the ability to create and manage work items in external project management systems. This capability mirrors how CodeWriterAgent has tools for file operations.\n\n## Objective\n\nCreate a tool abstraction for issue management that allows ProjectManagerAgent to create, update, and query issues. Implement GitHub as the first provider.\n\n## Expected Outcomes\n\n- Agents can create GitHub issues through typed tool interface\n- Hierarchical issue creation (epics + tasks) with dependencies\n- Abstraction supports future providers (Jira, Linear)\n\n## Subtasks\n\n1. Define Issue Management Tool Abstraction\n2. Create IssueTracker Provider Interface\n3. Implement GitHub Provider via gh CLI\n4. Implement Batch Issue Creation with Dependency Resolution\n5. Add Parent-Child Relationship Support\n6. Create IssueManagementExecutor\n7. Integration with ProjectManagerAgent",
          "labels": ["feature", "tools"],
          "parent": null,
          "dependsOn": []
        },
        {
          "localId": "issue-tool-task-1",
          "type": "Task",
          "title": "AMP-302.1: Define Issue Management Tool Abstraction",
          "body": "## Context\n\nFollowing the established Tool sealed hierarchy, define the issue management tools and data classes.\n\n## Objective\n\nDefine ToolCreateIssues, IssueCreateRequest, BatchIssueCreateRequest, and response types.\n\n## Validation\n\n- All data classes serialize/deserialize correctly\n- Tool follows existing interface pattern",
          "labels": ["task", "architecture"],
          "parent": "issue-tool-epic",
          "dependsOn": []
        },
        {
          "localId": "issue-tool-task-2",
          "type": "Task",
          "title": "AMP-302.2: Create IssueTracker Provider Interface",
          "body": "## Context\n\nTo support multiple issue tracking systems, we need a provider abstraction.\n\n## Objective\n\nDefine IssueTrackerProvider interface with methods for create, query, update operations.\n\n## Validation\n\n- Interface compiles and can be implemented\n- Result<T> return types for error handling",
          "labels": ["task", "architecture"],
          "parent": "issue-tool-epic",
          "dependsOn": ["issue-tool-task-1"]
        },
        {
          "localId": "issue-tool-task-3",
          "type": "Task",
          "title": "AMP-302.3: Implement GitHub Provider via gh CLI",
          "body": "## Context\n\nThe gh CLI provides reliable issue creation with built-in auth.\n\n## Objective\n\nImplement GitHubCliProvider that wraps gh commands.\n\n## Validation\n\n- validateConnection() detects auth state\n- createIssue() returns valid URL with issue number\n- Labels applied correctly",
          "labels": ["task", "implementation"],
          "parent": "issue-tool-epic",
          "dependsOn": ["issue-tool-task-2"]
        },
        {
          "localId": "issue-tool-task-4",
          "type": "Task",
          "title": "AMP-302.4: Implement Batch Issue Creation with Dependency Resolution",
          "body": "## Context\n\nCreating epics with tasks requires ordered creation for valid dependency references.\n\n## Objective\n\nImplement BatchIssueCreator with topological sorting.\n\n## Validation\n\n- Issues created in correct order\n- Dependencies contain valid issue numbers",
          "labels": ["task", "implementation"],
          "parent": "issue-tool-epic",
          "dependsOn": ["issue-tool-task-3"]
        },
        {
          "localId": "issue-tool-task-5",
          "type": "Task",
          "title": "AMP-302.5: Add Parent-Child Relationship Support",
          "body": "## Context\n\nGitHub sub-issues feature allows formal parent-child relationships.\n\n## Objective\n\nEnhance provider to maintain relationships through body updates and comments.\n\n## Validation\n\n- Parent issues list children as checkboxes\n- Cross-references create proper links",
          "labels": ["task", "implementation"],
          "parent": "issue-tool-epic",
          "dependsOn": ["issue-tool-task-4"]
        },
        {
          "localId": "issue-tool-task-6",
          "type": "Task",
          "title": "AMP-302.6: Create IssueManagementExecutor",
          "body": "## Context\n\nFollowing the Executor pattern for tool invocation.\n\n## Objective\n\nImplement executor that processes issue tools and emits events.\n\n## Validation\n\n- Executor routes to batch creator\n- Events emitted for observability",
          "labels": ["task", "implementation"],
          "parent": "issue-tool-epic",
          "dependsOn": ["issue-tool-task-4", "issue-tool-task-5"]
        },
        {
          "localId": "issue-tool-task-7",
          "type": "Task",
          "title": "AMP-302.7: Integration with ProjectManagerAgent",
          "body": "## Context\n\nProjectManagerAgent needs issue tools in its cognitive loop.\n\n## Objective\n\nWire ToolCreateIssues into PM's tools and update system prompt.\n\n## Validation\n\n- PM includes issue tools\n- End-to-end: goal → GitHub issues",
          "labels": ["task", "integration"],
          "parent": "issue-tool-epic",
          "dependsOn": ["issue-tool-task-6"]
        }
      ]
    }
    """.trimIndent()
}
