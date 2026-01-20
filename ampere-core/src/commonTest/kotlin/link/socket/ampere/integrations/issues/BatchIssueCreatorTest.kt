package link.socket.ampere.integrations.issues

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest
import link.socket.ampere.agents.execution.tools.issue.CreatedIssue
import link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest
import link.socket.ampere.agents.execution.tools.issue.IssueType

class BatchIssueCreatorTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `topological sort orders parents before children`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                // Child listed first
                IssueCreateRequest(
                    localId = "child",
                    type = IssueType.Task,
                    title = "Child",
                    body = "Child task",
                    parent = "parent",
                ),
                // Parent listed second
                IssueCreateRequest(
                    localId = "parent",
                    type = IssueType.Feature,
                    title = "Parent",
                    body = "Parent epic",
                ),
            ),
        )

        val response = creator.createBatch(request)

        // Verify parent was created first (should get issue #1)
        val parent = response.created.find { it.localId == "parent" }
        val child = response.created.find { it.localId == "child" }

        assertEquals(1, parent?.issueNumber)
        assertEquals(2, child?.issueNumber)

        // Verify creation order in mock
        assertEquals(listOf("parent", "child"), mockProvider.creationOrder)
    }

    @Test
    fun `topological sort orders dependencies before dependents`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                // Dependent listed first
                IssueCreateRequest(
                    localId = "dependent",
                    type = IssueType.Task,
                    title = "Dependent",
                    body = "Depends on other task",
                    dependsOn = listOf("dependency"),
                ),
                // Dependency listed second
                IssueCreateRequest(
                    localId = "dependency",
                    type = IssueType.Task,
                    title = "Dependency",
                    body = "Must be created first",
                ),
            ),
        )

        val response = creator.createBatch(request)

        // Verify dependency was created first
        val dependency = response.created.find { it.localId == "dependency" }
        val dependent = response.created.find { it.localId == "dependent" }

        assertEquals(1, dependency?.issueNumber)
        assertEquals(2, dependent?.issueNumber)

        assertEquals(listOf("dependency", "dependent"), mockProvider.creationOrder)
    }

    @Test
    fun `test data from spec sorts correctly`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val testJson = """
        {
          "repository": "socket-link/ampere",
          "issues": [
            {
              "localId": "task-2",
              "type": "Task",
              "title": "Task that depends on Task 1",
              "body": "This should be created second",
              "labels": ["task"],
              "parent": "epic-1",
              "dependsOn": ["task-1"]
            },
            {
              "localId": "epic-1",
              "type": "Feature",
              "title": "Epic created first despite being listed second",
              "body": "This should be created first",
              "labels": ["feature"],
              "parent": null,
              "dependsOn": []
            },
            {
              "localId": "task-1",
              "type": "Task",
              "title": "Task 1 under epic",
              "body": "This should be created after epic, before task-2",
              "labels": ["task"],
              "parent": "epic-1",
              "dependsOn": []
            }
          ]
        }
        """.trimIndent()

        val request = json.decodeFromString(BatchIssueCreateRequest.serializer(), testJson)
        val response = creator.createBatch(request)

        // Expected creation order: epic-1 → task-1 → task-2
        assertEquals(listOf("epic-1", "task-1", "task-2"), mockProvider.creationOrder)

        // Verify all were created successfully
        assertEquals(3, response.created.size)
        assertEquals(0, response.errors.size)
        assertTrue(response.success)
    }

    @Test
    fun `complex dependency graph sorts correctly`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                // Most dependent task (listed first to test sorting)
                IssueCreateRequest(
                    localId = "task-3",
                    type = IssueType.Task,
                    title = "Task 3",
                    body = "Depends on task-1 and task-2",
                    parent = "epic",
                    dependsOn = listOf("task-1", "task-2"),
                ),
                // Middle task
                IssueCreateRequest(
                    localId = "task-2",
                    type = IssueType.Task,
                    title = "Task 2",
                    body = "Depends on task-1",
                    parent = "epic",
                    dependsOn = listOf("task-1"),
                ),
                // Epic (parent)
                IssueCreateRequest(
                    localId = "epic",
                    type = IssueType.Feature,
                    title = "Epic",
                    body = "Parent of all tasks",
                ),
                // First task (no dependencies)
                IssueCreateRequest(
                    localId = "task-1",
                    type = IssueType.Task,
                    title = "Task 1",
                    body = "No dependencies",
                    parent = "epic",
                ),
            ),
        )

        val response = creator.createBatch(request)

        // Epic must be first (it's the parent)
        assertEquals("epic", mockProvider.creationOrder[0])

        // task-1 must come before task-2 and task-3
        val task1Index = mockProvider.creationOrder.indexOf("task-1")
        val task2Index = mockProvider.creationOrder.indexOf("task-2")
        val task3Index = mockProvider.creationOrder.indexOf("task-3")

        assertTrue(task1Index < task2Index, "task-1 should come before task-2")
        assertTrue(task1Index < task3Index, "task-1 should come before task-3")
        assertTrue(task2Index < task3Index, "task-2 should come before task-3")

        assertEquals(4, response.created.size)
        assertTrue(response.success)
    }

    @Test
    fun `resolves issue numbers correctly`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                IssueCreateRequest(
                    localId = "parent",
                    type = IssueType.Feature,
                    title = "Parent",
                    body = "Parent",
                ),
                IssueCreateRequest(
                    localId = "child",
                    type = IssueType.Task,
                    title = "Child",
                    body = "Child",
                    parent = "parent",
                    dependsOn = emptyList(),
                ),
            ),
        )

        val response = creator.createBatch(request)

        // Verify child knows its parent's issue number
        val child = response.created.find { it.localId == "child" }
        assertEquals(1, child?.parentIssueNumber) // Parent was issue #1
    }

    @Test
    fun `individual failures do not stop batch`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider(failOn = setOf("task-2"))
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                IssueCreateRequest(
                    localId = "task-1",
                    type = IssueType.Task,
                    title = "Task 1",
                    body = "Should succeed",
                ),
                IssueCreateRequest(
                    localId = "task-2",
                    type = IssueType.Task,
                    title = "Task 2",
                    body = "Should fail",
                ),
                IssueCreateRequest(
                    localId = "task-3",
                    type = IssueType.Task,
                    title = "Task 3",
                    body = "Should succeed",
                ),
            ),
        )

        val response = creator.createBatch(request)

        // Should have 2 successes and 1 failure
        assertEquals(2, response.created.size)
        assertEquals(1, response.errors.size)
        assertFalse(response.success)

        // Verify error details
        val error = response.errors[0]
        assertEquals("task-2", error.localId)
        assertTrue(error.message.contains("Simulated failure"))
    }

    @Test
    fun `empty batch returns empty response`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = emptyList(),
        )

        val response = creator.createBatch(request)

        assertEquals(0, response.created.size)
        assertEquals(0, response.errors.size)
        assertTrue(response.success)
    }

    @Test
    fun `handles cycles gracefully`() = runBlocking {
        // This test ensures we don't infinite loop on circular dependencies
        // In practice, agents should prevent this, but we should be robust
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                IssueCreateRequest(
                    localId = "task-1",
                    type = IssueType.Task,
                    title = "Task 1",
                    body = "Depends on task-2",
                    dependsOn = listOf("task-2"),
                ),
                IssueCreateRequest(
                    localId = "task-2",
                    type = IssueType.Task,
                    title = "Task 2",
                    body = "Depends on task-1",
                    dependsOn = listOf("task-1"),
                ),
            ),
        )

        // Should complete without hanging
        val response = creator.createBatch(request)

        // Both should be created (cycle detection prevents infinite recursion)
        assertEquals(2, response.created.size)
    }

    @Test
    fun `calls summarizeChildren for each parent`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                IssueCreateRequest(
                    localId = "parent1",
                    type = IssueType.Feature,
                    title = "Parent 1",
                    body = "First parent",
                ),
                IssueCreateRequest(
                    localId = "child1",
                    type = IssueType.Task,
                    title = "Child 1",
                    body = "Child of parent 1",
                    parent = "parent1",
                ),
                IssueCreateRequest(
                    localId = "parent2",
                    type = IssueType.Feature,
                    title = "Parent 2",
                    body = "Second parent",
                ),
                IssueCreateRequest(
                    localId = "child2",
                    type = IssueType.Task,
                    title = "Child 2",
                    body = "Child of parent 2",
                    parent = "parent2",
                ),
            ),
        )

        val response = creator.createBatch(request)

        // Verify summarizeChildren was called for both parents
        assertEquals(2, mockProvider.summarizeCalls.size)

        // Verify correct parent numbers were used
        assertTrue(mockProvider.summarizeCalls.contains(1)) // parent1 is issue #1
        assertTrue(mockProvider.summarizeCalls.contains(3)) // parent2 is issue #3
    }

    @Test
    fun `links dependencies using resolved issue numbers`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider()
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                IssueCreateRequest(
                    localId = "dependency",
                    type = IssueType.Task,
                    title = "Dependency",
                    body = "Created first",
                ),
                IssueCreateRequest(
                    localId = "dependent",
                    type = IssueType.Task,
                    title = "Dependent",
                    body = "Depends on dependency",
                    dependsOn = listOf("dependency"),
                ),
            ),
        )

        creator.createBatch(request)

        val linkCalls = mockProvider.dependencyLinks
        assertEquals(1, linkCalls.size)
        assertEquals(2, linkCalls[0].issueNumber) // dependent is issue #2
        assertEquals(listOf(1), linkCalls[0].dependsOnIssueNumbers) // dependency is issue #1
    }

    @Test
    fun `does not link unresolved dependencies`() = runBlocking {
        val mockProvider = MockIssueTrackerProvider(failOn = setOf("dependency"))
        val creator = BatchIssueCreator(mockProvider)

        val request = BatchIssueCreateRequest(
            repository = "owner/repo",
            issues = listOf(
                IssueCreateRequest(
                    localId = "dependency",
                    type = IssueType.Task,
                    title = "Dependency",
                    body = "Fails to create",
                ),
                IssueCreateRequest(
                    localId = "dependent",
                    type = IssueType.Task,
                    title = "Dependent",
                    body = "Depends on dependency",
                    dependsOn = listOf("dependency"),
                ),
            ),
        )

        creator.createBatch(request)

        assertEquals(0, mockProvider.dependencyLinks.size)
    }
}

/**
 * Mock provider for testing batch creation logic.
 */
private class MockIssueTrackerProvider(
    private val failOn: Set<String> = emptySet(),
) : IssueTrackerProvider {

    override val providerId = "mock"
    override val displayName = "Mock Provider"

    val creationOrder = mutableListOf<String>()
    val summarizeCalls = mutableListOf<Int>() // Track which parent numbers got summarized
    val dependencyLinks = mutableListOf<DependencyLinkCall>()
    private var nextIssueNumber = 1

    override suspend fun validateConnection(): Result<Unit> = Result.success(Unit)

    override suspend fun createIssue(
        repository: String,
        request: IssueCreateRequest,
        resolvedDependencies: Map<String, Int>,
    ): Result<CreatedIssue> {
        creationOrder.add(request.localId)

        if (request.localId in failOn) {
            return Result.failure(Exception("Simulated failure for ${request.localId}"))
        }

        val issueNumber = nextIssueNumber++
        return Result.success(
            CreatedIssue(
                localId = request.localId,
                issueNumber = issueNumber,
                url = "https://example.com/issues/$issueNumber",
                parentIssueNumber = request.parent?.let { resolvedDependencies[it] },
            ),
        )
    }

    override suspend fun setParentRelationship(
        repository: String,
        childIssueNumber: Int,
        parentIssueNumber: Int,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun linkDependencies(
        repository: String,
        issueNumber: Int,
        dependsOnIssueNumbers: List<Int>,
    ): Result<Unit> {
        dependencyLinks.add(DependencyLinkCall(issueNumber, dependsOnIssueNumbers))
        return Result.success(Unit)
    }

    override suspend fun queryIssues(
        repository: String,
        query: IssueQuery,
    ): Result<List<ExistingIssue>> = Result.success(emptyList())

    override suspend fun updateIssue(
        repository: String,
        issueNumber: Int,
        update: IssueUpdate,
    ): Result<ExistingIssue> = Result.failure(Exception("Not implemented"))

    override suspend fun summarizeChildren(
        repository: String,
        parentNumber: Int,
        children: List<CreatedIssue>,
    ): Result<Unit> {
        summarizeCalls.add(parentNumber)
        return Result.success(Unit)
    }
}

private data class DependencyLinkCall(
    val issueNumber: Int,
    val dependsOnIssueNumbers: List<Int>,
)
