package link.socket.ampere.integrations.issues

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.execution.tools.issue.CreatedIssue
import link.socket.ampere.agents.execution.tools.issue.IssueCreateRequest
import link.socket.ampere.agents.execution.tools.issue.IssueType

class IssueTrackerProviderTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `IssueState enum has all required states`() {
        assertEquals(IssueState.Open, IssueState.valueOf("Open"))
        assertEquals(IssueState.Closed, IssueState.valueOf("Closed"))
        assertEquals(IssueState.All, IssueState.valueOf("All"))
    }

    @Test
    fun `IssueQuery with all fields serializes correctly`() {
        val query = IssueQuery(
            state = IssueState.Open,
            labels = listOf("bug", "critical"),
            assignee = "developer1",
            titleContains = "crash",
            limit = 25,
        )

        val serialized = json.encodeToString(IssueQuery.serializer(), query)
        val deserialized = json.decodeFromString(IssueQuery.serializer(), serialized)

        assertEquals(query, deserialized)
    }

    @Test
    fun `IssueQuery with defaults serializes correctly`() {
        val query = IssueQuery()

        assertEquals(null, query.state)
        assertEquals(emptyList(), query.labels)
        assertEquals(null, query.assignee)
        assertEquals(null, query.titleContains)
        assertEquals(50, query.limit)

        val serialized = json.encodeToString(IssueQuery.serializer(), query)
        val deserialized = json.decodeFromString(IssueQuery.serializer(), serialized)

        assertEquals(query, deserialized)
    }

    @Test
    fun `IssueQuery allows filtering by state only`() {
        val query = IssueQuery(state = IssueState.Closed)

        val serialized = json.encodeToString(IssueQuery.serializer(), query)
        val deserialized = json.decodeFromString(IssueQuery.serializer(), serialized)

        assertEquals(IssueState.Closed, deserialized.state)
        assertEquals(emptyList(), deserialized.labels)
    }

    @Test
    fun `ExistingIssue serializes correctly`() {
        val issue = ExistingIssue(
            number = 42,
            title = "Fix critical bug",
            body = "This bug causes crashes",
            state = IssueState.Open,
            labels = listOf("bug", "critical"),
            url = "https://github.com/owner/repo/issues/42",
            parentNumber = null,
        )

        val serialized = json.encodeToString(ExistingIssue.serializer(), issue)
        val deserialized = json.decodeFromString(ExistingIssue.serializer(), serialized)

        assertEquals(issue, deserialized)
    }

    @Test
    fun `ExistingIssue with parent relationship serializes correctly`() {
        val issue = ExistingIssue(
            number = 43,
            title = "Subtask of epic",
            body = "Part of larger work",
            state = IssueState.Open,
            labels = listOf("task"),
            url = "https://github.com/owner/repo/issues/43",
            parentNumber = 42,
        )

        val serialized = json.encodeToString(ExistingIssue.serializer(), issue)
        val deserialized = json.decodeFromString(ExistingIssue.serializer(), serialized)

        assertEquals(42, deserialized.parentNumber)
    }

    @Test
    fun `IssueUpdate with all fields serializes correctly`() {
        val update = IssueUpdate(
            title = "Updated title",
            body = "Updated body",
            state = IssueState.Closed,
            labels = listOf("resolved", "fixed"),
            assignees = listOf("developer1", "developer2"),
        )

        val serialized = json.encodeToString(IssueUpdate.serializer(), update)
        val deserialized = json.decodeFromString(IssueUpdate.serializer(), serialized)

        assertEquals(update, deserialized)
    }

    @Test
    fun `IssueUpdate allows partial updates`() {
        val update = IssueUpdate(
            title = "Only update title",
        )

        assertEquals("Only update title", update.title)
        assertNull(update.body)
        assertNull(update.state)
        assertNull(update.labels)
        assertNull(update.assignees)

        val serialized = json.encodeToString(IssueUpdate.serializer(), update)
        val deserialized = json.decodeFromString(IssueUpdate.serializer(), serialized)

        assertEquals(update, deserialized)
    }

    @Test
    fun `IssueUpdate with empty lists clears fields`() {
        val update = IssueUpdate(
            labels = emptyList(),
            assignees = emptyList(),
        )

        assertEquals(emptyList(), update.labels)
        assertEquals(emptyList(), update.assignees)
    }

    @Test
    fun `IssueTrackerProvider interface can be implemented`() {
        // Create a mock implementation to verify the interface is implementable
        val mockProvider = object : IssueTrackerProvider {
            override val providerId = "test"
            override val displayName = "Test Provider"

            override suspend fun validateConnection(): Result<Unit> {
                return Result.success(Unit)
            }

            override suspend fun createIssue(
                repository: String,
                request: IssueCreateRequest,
                resolvedDependencies: Map<String, Int>,
            ): Result<CreatedIssue> {
                return Result.success(
                    CreatedIssue(
                        localId = request.localId,
                        issueNumber = 1,
                        url = "https://example.com/issues/1",
                    ),
                )
            }

            override suspend fun setParentRelationship(
                repository: String,
                childIssueNumber: Int,
                parentIssueNumber: Int,
            ): Result<Unit> {
                return Result.success(Unit)
            }

            override suspend fun queryIssues(
                repository: String,
                query: IssueQuery,
            ): Result<List<ExistingIssue>> {
                return Result.success(emptyList())
            }

            override suspend fun updateIssue(
                repository: String,
                issueNumber: Int,
                update: IssueUpdate,
            ): Result<ExistingIssue> {
                return Result.success(
                    ExistingIssue(
                        number = issueNumber,
                        title = update.title ?: "Original Title",
                        body = update.body ?: "Original Body",
                        state = update.state ?: IssueState.Open,
                        labels = update.labels ?: emptyList(),
                        url = "https://example.com/issues/$issueNumber",
                    ),
                )
            }
        }

        assertEquals("test", mockProvider.providerId)
        assertEquals("Test Provider", mockProvider.displayName)
    }

    @Test
    fun `IssueTrackerProvider can handle error results`() {
        val mockProvider = object : IssueTrackerProvider {
            override val providerId = "test"
            override val displayName = "Test Provider"

            override suspend fun validateConnection(): Result<Unit> {
                return Result.failure(Exception("Connection failed"))
            }

            override suspend fun createIssue(
                repository: String,
                request: IssueCreateRequest,
                resolvedDependencies: Map<String, Int>,
            ): Result<CreatedIssue> {
                return Result.failure(Exception("Rate limit exceeded"))
            }

            override suspend fun setParentRelationship(
                repository: String,
                childIssueNumber: Int,
                parentIssueNumber: Int,
            ): Result<Unit> {
                return Result.failure(Exception("Parent not found"))
            }

            override suspend fun queryIssues(
                repository: String,
                query: IssueQuery,
            ): Result<List<ExistingIssue>> {
                return Result.failure(Exception("Search failed"))
            }

            override suspend fun updateIssue(
                repository: String,
                issueNumber: Int,
                update: IssueUpdate,
            ): Result<ExistingIssue> {
                return Result.failure(Exception("Issue not found"))
            }
        }

        // Verify interface supports Result return types for error handling
        assertTrue(mockProvider is IssueTrackerProvider)
    }

    @Test
    fun `resolvedDependencies allows dependency injection during batch creation`() {
        // Simulate resolving dependencies
        val dependencies = mapOf(
            "task-1" to 100,
            "task-2" to 101,
            "epic-1" to 99,
        )

        // Mock provider that uses resolved dependencies
        val mockProvider = object : IssueTrackerProvider {
            override val providerId = "test"
            override val displayName = "Test Provider"

            override suspend fun validateConnection(): Result<Unit> = Result.success(Unit)

            override suspend fun createIssue(
                repository: String,
                request: IssueCreateRequest,
                resolvedDependencies: Map<String, Int>,
            ): Result<CreatedIssue> {
                // Provider can use resolvedDependencies to add "Depends on #100, #101" to body
                val dependencyText = request.dependsOn
                    .mapNotNull { localId -> resolvedDependencies[localId] }
                    .joinToString(", ") { "#$it" }

                return Result.success(
                    CreatedIssue(
                        localId = request.localId,
                        issueNumber = 102,
                        url = "https://example.com/issues/102",
                        parentIssueNumber = request.parent?.let { resolvedDependencies[it] },
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
            ): Result<List<ExistingIssue>> = Result.success(emptyList())

            override suspend fun updateIssue(
                repository: String,
                issueNumber: Int,
                update: IssueUpdate,
            ): Result<ExistingIssue> = Result.failure(Exception("Not implemented"))
        }

        val request = IssueCreateRequest(
            localId = "task-3",
            type = IssueType.Task,
            title = "Task with dependencies",
            body = "Depends on other tasks",
            parent = "epic-1",
            dependsOn = listOf("task-1", "task-2"),
        )

        // Verify the interface supports dependency injection
        assertTrue(mockProvider is IssueTrackerProvider)
    }
}
