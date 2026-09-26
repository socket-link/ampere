package link.socket.ampere.work.linear

import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult

/**
 * Responses and tool schemas recorded from the live work source on
 * **2026-09-26**, verbatim except where noted.
 *
 * These are the response-shape half of the schema pin. MCP `inputSchema` says
 * nothing about what comes back, and the bodies are as vendor-controlled and
 * unversioned as the requests, so the only way to pin a response is to decode a
 * recording of one. A renamed response field fails
 * [WorkSourceDecodingTest] here rather than producing a silently empty page in
 * production.
 *
 * The recording is a *witness*, not the contract: the contract is
 * [WorkSourceToolPins], and the live check is
 * [LinearWorkSource.open], which verifies the server's advertised surface before
 * the adapter makes a call. Re-record these when the vendor changes the surface;
 * do not edit them to make a test pass.
 */
object Recorded {

    /**
     * `get_issue` with `includeRelations: true`, trimmed to the fields this
     * adapter reads plus enough neighbours to show the shape it reads them out
     * of. `stateHistory` and `relations` are verbatim.
     */
    val GET_ISSUE: String = """
        {
          "id": "AMPR-305",
          "uuid": "e6611e74-52f1-4076-8de8-8a4d612fd059",
          "title": "Work-source adapter: Chassis SPI Plug over MCP",
          "priority": { "value": 2, "name": "High" },
          "url": "https://example.invalid/issue/AMPR-305",
          "createdAt": "2026-08-24T03:30:16.284Z",
          "updatedAt": "2026-09-26T06:14:50.333Z",
          "startedAt": "2026-09-26T06:14:50.317Z",
          "completedAt": null,
          "dueDate": null,
          "status": "In Progress",
          "statusType": "started",
          "labels": ["wave:w0", "api", "integration", "cli"],
          "stateHistory": [
            {
              "state": { "id": "96353e8a", "name": "Backlog", "type": "backlog" },
              "startedAt": "2026-08-24T03:30:16.284Z",
              "endedAt": "2026-09-26T06:14:50.328Z"
            },
            {
              "state": { "id": "85622bc9", "name": "In Progress", "type": "started" },
              "startedAt": "2026-09-26T06:14:50.328Z",
              "endedAt": null
            }
          ],
          "project": "Act 7 - CLI Dispatch Loop",
          "projectId": "2383a667-44c6-458c-9c5d-5c31855040aa",
          "parentId": "AMPR-286",
          "team": "Ampere",
          "teamId": "5dbd9ca7-68f5-4692-a4e7-da1c09092f69",
          "relations": {
            "blocks": [{ "id": "AMPR-314", "title": "Admit four supervisory lifecycle states" }],
            "blockedBy": [],
            "relatedTo": [{ "id": "AMPR-289", "title": "Recon: work-source integration surface" }],
            "duplicateOf": null
          }
        }
    """.trimIndent()

    /**
     * `list_issues` with an explicit `fields` selection, two results and another
     * page waiting.
     *
     * Note what a listed issue carries and a fetched one does not, and vice
     * versa: `projectId` but no `project`, `team` but no `teamId`, and neither
     * `relations` nor `stateHistory`. That asymmetry is why
     * [WorkSourceIssue.blockedBy] distinguishes "not read" from "none".
     */
    val LIST_ISSUES: String = """
        {
          "issues": [
            {
              "id": "AMPR-305",
              "uuid": "e6611e74-52f1-4076-8de8-8a4d612fd059",
              "title": "Work-source adapter: Chassis SPI Plug over MCP",
              "status": "In Progress",
              "statusType": "started",
              "labels": ["wave:w0", "api", "integration", "cli"],
              "updatedAt": "2026-09-26T06:14:50.333Z",
              "url": "https://example.invalid/issue/AMPR-305",
              "projectId": "2383a667-44c6-458c-9c5d-5c31855040aa",
              "team": "Ampere"
            },
            {
              "id": "AMPR-333",
              "uuid": "f7b3c117-d8a2-47fc-bb75-d0283d3c0dcc",
              "title": "DatabaseSchemaManager: run migrations on JVM opens",
              "status": "Done",
              "statusType": "completed",
              "labels": ["wave:w0", "ready", "infrastructure", "migration"],
              "updatedAt": "2026-09-21T16:18:10.886Z",
              "url": "https://example.invalid/issue/AMPR-333",
              "projectId": "846a5eab-08b4-47f9-ab18-6109e466035a",
              "team": "Ampere"
            }
          ],
          "hasNextPage": true,
          "cursor": "f7b3c117-d8a2-47fc-bb75-d0283d3c0dcc"
        }
    """.trimIndent()

    /**
     * `list_comments`, showing the two server-assigned facts the claim arbiter
     * runs on: the comment `id` and its millisecond `createdAt`.
     */
    val LIST_COMMENTS: String = """
        {
          "comments": [
            {
              "id": "f0e41cac-70c7-44a8-bcf2-752b28be5755",
              "body": "claim:AMPR-305:supervisor-a",
              "attachments": [],
              "createdAt": "2026-08-24T02:27:16.214Z",
              "updatedAt": "2026-08-24T02:50:13.699Z",
              "parentId": null,
              "resolvedAt": null,
              "quotedText": null,
              "author": { "id": "2fb7d01c", "name": "Miley Chandonnet" },
              "onBehalfOf": null
            },
            {
              "id": "71ba9057-8a66-4879-bce4-c2a805b4f0ea",
              "body": "This comment thread is synced to a corresponding GitHub issue.",
              "attachments": [],
              "createdAt": "2026-08-24T02:12:47.544Z",
              "updatedAt": "2026-08-24T02:12:47.505Z",
              "parentId": null,
              "resolvedAt": null,
              "quotedText": null,
              "author": null,
              "onBehalfOf": null
            }
          ],
          "hasNextPage": false
        }
    """.trimIndent()

    /**
     * The advertised tool surface, recorded as of the date above and trimmed to
     * the tools this adapter calls.
     *
     * Each schema keeps every property [WorkSourceToolPins] names *plus* a few it
     * does not, so a passing pin test proves [WorkSourceToolPins.verify] accepts
     * a surface richer than the pins rather than one that mirrors them.
     */
    val TOOL_SURFACE: List<McpToolDescriptor> = listOf(
        descriptor(
            name = "list_issues",
            description = "List issues in the user's workspace.",
            schema = """
                {
                  "type": "object",
                  "properties": {
                    "assignee": { "type": ["string", "null"] },
                    "createdAt": { "type": "string" },
                    "cursor": { "type": "string" },
                    "fields": {
                      "type": "array",
                      "items": {
                        "type": "string",
                        "enum": [
                          "id", "uuid", "title", "description", "priority", "estimate", "url",
                          "gitBranchName", "createdAt", "updatedAt", "archivedAt", "completedAt",
                          "startedAt", "canceledAt", "dueDate", "status", "statusType", "labels",
                          "createdBy", "assignee", "project", "projectId", "parentId", "team",
                          "teamId", "cycleId"
                        ]
                      }
                    },
                    "label": { "type": "string" },
                    "limit": { "type": "number" },
                    "orderBy": { "type": "string", "enum": ["createdAt", "updatedAt"] },
                    "priority": { "type": "number" },
                    "project": { "type": "string" },
                    "query": { "type": "string" },
                    "state": { "type": "string" },
                    "team": { "type": "string" },
                    "updatedAt": { "type": "string" }
                  }
                }
            """,
        ),
        descriptor(
            name = "get_issue",
            description = "Retrieve detailed information about an issue by ID.",
            schema = """
                {
                  "type": "object",
                  "properties": {
                    "id": { "type": "string" },
                    "includeCustomerNeeds": { "type": "boolean", "default": false },
                    "includeRelations": { "type": "boolean", "default": false },
                    "includeReleases": { "type": "boolean", "default": false }
                  },
                  "required": ["id"]
                }
            """,
        ),
        descriptor(
            name = "save_issue",
            description = "Create or update an issue.",
            schema = """
                {
                  "type": "object",
                  "properties": {
                    "addLabels": { "type": "array", "items": { "type": "string" } },
                    "assignee": { "type": ["string", "null"] },
                    "description": { "type": "string" },
                    "dueDate": { "type": ["string", "null"] },
                    "id": { "type": "string" },
                    "labels": { "type": "array", "items": { "type": "string" } },
                    "priority": { "type": "number" },
                    "project": { "type": ["string", "null"] },
                    "removeLabels": { "type": "array", "items": { "type": "string" } },
                    "state": { "type": "string" },
                    "team": { "type": "string" },
                    "title": { "type": "string" }
                  }
                }
            """,
        ),
        descriptor(
            name = "list_comments",
            description = "List comments on an issue.",
            schema = """
                {
                  "type": "object",
                  "properties": {
                    "cursor": { "type": "string" },
                    "documentId": { "type": "string" },
                    "issueId": { "type": "string" },
                    "limit": { "type": "number" },
                    "orderBy": { "type": "string", "enum": ["createdAt", "updatedAt"] },
                    "projectId": { "type": "string" }
                  }
                }
            """,
        ),
        descriptor(
            name = "save_comment",
            description = "Create or update a comment.",
            schema = """
                {
                  "type": "object",
                  "properties": {
                    "body": { "type": "string" },
                    "id": { "type": "string" },
                    "issueId": { "type": "string" },
                    "parentId": { "type": "string" }
                  },
                  "required": ["body"]
                }
            """,
        ),
    )

    fun descriptor(name: String, description: String, schema: String): McpToolDescriptor =
        McpToolDescriptor(
            name = name,
            description = description,
            inputSchema = WORK_SOURCE_JSON.parseToJsonElement(schema.trimIndent()) as JsonObject,
        )

    fun body(json: String): JsonObject =
        WORK_SOURCE_JSON.parseToJsonElement(json) as JsonObject
}

/** Wraps a JSON body the way the work source returns it: one `text` content item. */
fun textResult(json: String, isError: Boolean = false): ToolCallResult =
    ToolCallResult(content = listOf(ContentItem(type = "text", text = json)), isError = isError)
