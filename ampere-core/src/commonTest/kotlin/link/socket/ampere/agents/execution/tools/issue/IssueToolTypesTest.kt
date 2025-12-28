package link.socket.ampere.agents.execution.tools.issue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class IssueToolTypesTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `IssueType enum covers needed issue types`() {
        // Verify all expected issue types exist
        assertEquals(IssueType.Feature, IssueType.valueOf("Feature"))
        assertEquals(IssueType.Task, IssueType.valueOf("Task"))
        assertEquals(IssueType.Bug, IssueType.valueOf("Bug"))
        assertEquals(IssueType.Spike, IssueType.valueOf("Spike"))
    }

    @Test
    fun `IssueCreateRequest serializes correctly`() {
        val request = IssueCreateRequest(
            localId = "test-1",
            type = IssueType.Task,
            title = "Test Task",
            body = "Task description",
            labels = listOf("task", "test"),
            assignees = emptyList(),
            parent = "epic-1",
            dependsOn = emptyList(),
        )

        val serialized = json.encodeToString(IssueCreateRequest.serializer(), request)
        val deserialized = json.decodeFromString(IssueCreateRequest.serializer(), serialized)

        assertEquals(request, deserialized)
    }

    @Test
    fun `BatchIssueCreateRequest with test data parses correctly`() {
        val testJson = """
        {
          "repository": "socket-link/ampere",
          "issues": [
            {
              "localId": "test-epic",
              "type": "Feature",
              "title": "Test Epic for Tool Validation",
              "body": "This epic was created to test the issue management tool.",
              "labels": ["feature", "test"],
              "assignees": [],
              "parent": null,
              "dependsOn": []
            },
            {
              "localId": "test-task-1",
              "type": "Task",
              "title": "Test Task 1",
              "body": "First test task under the epic.",
              "labels": ["task", "test"],
              "assignees": [],
              "parent": "test-epic",
              "dependsOn": []
            }
          ]
        }
        """.trimIndent()

        val request = json.decodeFromString(BatchIssueCreateRequest.serializer(), testJson)

        assertEquals("socket-link/ampere", request.repository)
        assertEquals(2, request.issues.size)

        // Verify epic
        val epic = request.issues[0]
        assertEquals("test-epic", epic.localId)
        assertEquals(IssueType.Feature, epic.type)
        assertEquals("Test Epic for Tool Validation", epic.title)
        assertEquals(null, epic.parent)
        assertEquals(emptyList(), epic.dependsOn)
        assertTrue(epic.labels.contains("feature"))

        // Verify task
        val task = request.issues[1]
        assertEquals("test-task-1", task.localId)
        assertEquals(IssueType.Task, task.type)
        assertEquals("Test Task 1", task.title)
        assertEquals("test-epic", task.parent)
        assertEquals(emptyList(), task.dependsOn)
        assertTrue(task.labels.contains("task"))
    }

    @Test
    fun `CreatedIssue serializes with parent reference`() {
        val created = CreatedIssue(
            localId = "task-1",
            issueNumber = 43,
            url = "https://github.com/owner/repo/issues/43",
            parentIssueNumber = 42,
        )

        val serialized = json.encodeToString(CreatedIssue.serializer(), created)
        val deserialized = json.decodeFromString(CreatedIssue.serializer(), serialized)

        assertEquals(created, deserialized)
        assertEquals(42, deserialized.parentIssueNumber)
    }

    @Test
    fun `BatchIssueCreateResponse serializes success state`() {
        val response = BatchIssueCreateResponse(
            success = true,
            created = listOf(
                CreatedIssue(
                    localId = "epic-1",
                    issueNumber = 42,
                    url = "https://github.com/owner/repo/issues/42",
                    parentIssueNumber = null,
                ),
                CreatedIssue(
                    localId = "task-1",
                    issueNumber = 43,
                    url = "https://github.com/owner/repo/issues/43",
                    parentIssueNumber = 42,
                ),
            ),
            errors = emptyList(),
        )

        val serialized = json.encodeToString(BatchIssueCreateResponse.serializer(), response)
        val deserialized = json.decodeFromString(BatchIssueCreateResponse.serializer(), serialized)

        assertTrue(deserialized.success)
        assertEquals(2, deserialized.created.size)
        assertEquals(0, deserialized.errors.size)
    }

    @Test
    fun `BatchIssueCreateResponse serializes failure state`() {
        val response = BatchIssueCreateResponse(
            success = false,
            created = listOf(
                CreatedIssue(
                    localId = "epic-1",
                    issueNumber = 42,
                    url = "https://github.com/owner/repo/issues/42",
                ),
            ),
            errors = listOf(
                IssueCreateError(
                    localId = "task-1",
                    message = "Rate limit exceeded",
                ),
            ),
        )

        val serialized = json.encodeToString(BatchIssueCreateResponse.serializer(), response)
        val deserialized = json.decodeFromString(BatchIssueCreateResponse.serializer(), serialized)

        assertFalse(deserialized.success)
        assertEquals(1, deserialized.created.size)
        assertEquals(1, deserialized.errors.size)
        assertEquals("Rate limit exceeded", deserialized.errors[0].message)
    }

    @Test
    fun `IssueCreateRequest with dependencies serializes correctly`() {
        val request = IssueCreateRequest(
            localId = "task-3",
            type = IssueType.Task,
            title = "Task with dependencies",
            body = "This task depends on other tasks",
            labels = listOf("task"),
            assignees = listOf("developer1"),
            parent = "epic-1",
            dependsOn = listOf("task-1", "task-2"),
        )

        val serialized = json.encodeToString(IssueCreateRequest.serializer(), request)
        val deserialized = json.decodeFromString(IssueCreateRequest.serializer(), serialized)

        assertEquals(request, deserialized)
        assertEquals(2, deserialized.dependsOn.size)
        assertTrue(deserialized.dependsOn.contains("task-1"))
        assertTrue(deserialized.dependsOn.contains("task-2"))
    }
}
