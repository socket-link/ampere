package link.socket.ampere.work.linear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor

/**
 * The tool surface is vendor-controlled and unversioned. These tests are the
 * tripwire: the pins accept the surface as recorded, and reject every shape of
 * drift rather than degrading quietly.
 *
 * A renamed `label` argument does not error at the server — it returns a page
 * that ignored the filter, and the ready queue starts offering tickets from
 * every wave. That is the failure these tests exist to make loud.
 */
class WorkSourceToolPinsTest {

    @Test
    fun `the recorded tool surface satisfies every pin`() {
        assertTrue(
            WorkSourceToolPins.verifyDescriptors(Recorded.TOOL_SURFACE).isSuccess,
            "the recorded surface must satisfy the pins it was recorded for",
        )
    }

    @Test
    fun `a surface richer than the pins still satisfies them`() {
        // The pins are a floor, not a mirror: a vendor adding tools and
        // arguments is not drift, and a pin set that failed on additions would
        // be re-recorded so often it would stop being a tripwire.
        val richer = Recorded.TOOL_SURFACE + Recorded.descriptor(
            name = "list_projects",
            description = "Something this adapter never calls.",
            schema = """{ "type": "object", "properties": { "team": { "type": "string" } } }""",
        )

        assertTrue(WorkSourceToolPins.verifyDescriptors(richer).isSuccess)
    }

    @Test
    fun `a renamed tool fails the pin`() {
        val renamed = Recorded.TOOL_SURFACE.map { descriptor ->
            if (descriptor.name == WorkSourceToolPins.GET_ISSUE) {
                descriptor.copy(name = "fetch_issue")
            } else {
                descriptor
            }
        }

        val drift = renamed.drift()

        assertEquals(setOf(WorkSourceToolPins.GET_ISSUE), drift.missingTools)
    }

    @Test
    fun `a removed tool fails the pin`() {
        val without = Recorded.TOOL_SURFACE.filterNot { it.name == WorkSourceToolPins.SAVE_COMMENT }

        assertEquals(setOf(WorkSourceToolPins.SAVE_COMMENT), without.drift().missingTools)
    }

    @Test
    fun `a renamed argument fails the pin`() {
        val renamed = Recorded.TOOL_SURFACE.withSchema(WorkSourceToolPins.LIST_ISSUES) {
            """
            {
              "type": "object",
              "properties": {
                "labels": { "type": "string" },
                "state": { "type": "string" },
                "project": { "type": "string" },
                "team": { "type": "string" },
                "limit": { "type": "number" },
                "cursor": { "type": "string" },
                "updatedAt": { "type": "string" },
                "fields": { "type": "array", "items": { "type": "string", "enum": ["id"] } }
              }
            }
            """
        }

        val drift = renamed.drift()

        assertEquals(
            mapOf(WorkSourceToolPins.LIST_ISSUES to setOf("label")),
            drift.missingArguments,
            "a `label` argument renamed to `labels` must be named, not shrugged at",
        )
    }

    @Test
    fun `a retired fields enum member fails the pin`() {
        val retired = Recorded.TOOL_SURFACE.withSchema(WorkSourceToolPins.LIST_ISSUES) {
            """
            {
              "type": "object",
              "properties": {
                "label": { "type": "string" },
                "state": { "type": "string" },
                "project": { "type": "string" },
                "team": { "type": "string" },
                "limit": { "type": "number" },
                "cursor": { "type": "string" },
                "updatedAt": { "type": "string" },
                "fields": {
                  "type": "array",
                  "items": { "type": "string", "enum": ["id", "title", "status", "labels"] }
                }
              }
            }
            """
        }

        val drift = retired.drift()

        assertEquals(
            setOf("uuid", "statusType", "updatedAt", "url", "projectId", "team"),
            drift.missingArgumentValues["${WorkSourceToolPins.LIST_ISSUES}.fields"],
            "the fields vocabulary is closed server-side, so a retired member breaks every read",
        )
    }

    @Test
    fun `an argument that lost its enum fails the pin`() {
        val flattened = Recorded.TOOL_SURFACE.withSchema(WorkSourceToolPins.LIST_COMMENTS) {
            """
            {
              "type": "object",
              "properties": {
                "issueId": { "type": "string" },
                "limit": { "type": "number" },
                "cursor": { "type": "string" },
                "orderBy": { "type": "string" }
              }
            }
            """
        }

        assertEquals(
            setOf(WorkSourceToolPins.COMMENT_ORDER_BY),
            flattened.drift().missingArgumentValues["${WorkSourceToolPins.LIST_COMMENTS}.orderBy"],
        )
    }

    @Test
    fun `a tool advertising no schema fails every pinned argument`() {
        val schemaless = Recorded.TOOL_SURFACE.map { descriptor ->
            if (descriptor.name == WorkSourceToolPins.SAVE_ISSUE) {
                descriptor.copy(inputSchema = null)
            } else {
                descriptor
            }
        }

        val drift = schemaless.drift()

        assertTrue(drift.missingTools.isEmpty(), "the tool is present; its schema is not")
        assertEquals(
            setOf("id", "state", "addLabels", "removeLabels"),
            drift.missingArguments[WorkSourceToolPins.SAVE_ISSUE],
        )
    }

    @Test
    fun `every drifted tool and argument is reported in one failure`() {
        val doubleDrift = Recorded.TOOL_SURFACE
            .filterNot { it.name == WorkSourceToolPins.SAVE_COMMENT }
            .withSchema(WorkSourceToolPins.GET_ISSUE) {
                """{ "type": "object", "properties": { "id": { "type": "string" } } }"""
            }

        val drift = doubleDrift.drift()

        assertEquals(setOf(WorkSourceToolPins.SAVE_COMMENT), drift.missingTools)
        assertEquals(
            mapOf(WorkSourceToolPins.GET_ISSUE to setOf("includeRelations")),
            drift.missingArguments,
        )
        assertTrue(WorkSourceFailure.SchemaDrift().isDrift.not(), "no drift means no drift")
    }

    @Test
    fun `a drift describes itself in one line`() {
        val described = WorkSourceFailure.SchemaDrift(
            missingTools = setOf("get_issue"),
            missingArguments = mapOf("list_issues" to setOf("label")),
        ).describe()

        assertTrue("get_issue" in described, described)
        assertTrue("label" in described, described)
    }

    /**
     * The `open()` path verifies against `PlugContext`-discovered tools, which
     * carry the *local* `dependency:tool` id and the remote name separately.
     * Pinning against the wrong one would pass in a test and fail on the wire.
     */
    @Test
    fun `pins verify against a plug context's tools by remote name`() {
        val tools = Recorded.TOOL_SURFACE.map { descriptor ->
            McpTool(
                id = "${LinearWorkSource.DEPENDENCY_NAME}:${descriptor.name}",
                name = descriptor.name,
                description = descriptor.description,
                requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
                serverId = "https://example.invalid/mcp",
                remoteToolName = descriptor.name,
                inputSchema = descriptor.inputSchema,
            )
        }

        assertTrue(WorkSourceToolPins.verifyTools(tools).isSuccess)
    }

    private fun List<McpToolDescriptor>.drift(): WorkSourceFailure.SchemaDrift {
        val failure = WorkSourceToolPins.verifyDescriptors(this).exceptionOrNull()
        val workSource = failure as? WorkSourceException
            ?: throw AssertionError("expected a WorkSourceException, got $failure")
        return workSource.failure as? WorkSourceFailure.SchemaDrift
            ?: throw AssertionError("expected SchemaDrift, got ${workSource.failure}")
    }

    private fun List<McpToolDescriptor>.withSchema(
        tool: String,
        schema: () -> String,
    ): List<McpToolDescriptor> = map { descriptor ->
        if (descriptor.name == tool) {
            descriptor.copy(
                inputSchema = WORK_SOURCE_JSON.parseToJsonElement(schema().trimIndent()) as JsonObject,
            )
        } else {
            descriptor
        }
    }
}
