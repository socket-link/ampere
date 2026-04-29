package link.socket.ampere.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.knowledge.InMemoryKnowledgeStore
import link.socket.ampere.knowledge.KnowledgeDocument
import link.socket.ampere.knowledge.KnowledgeScope
import link.socket.ampere.knowledge.QueryMode
import link.socket.ampere.plugin.PluginManifest
import link.socket.ampere.plugin.permission.GateResult
import link.socket.ampere.plugin.permission.PluginPermission
import link.socket.ampere.plugin.permission.PluginPermissionGate
import link.socket.ampere.plugin.permission.PluginToolCall
import link.socket.ampere.plugin.permission.UserGrants

class KnowledgeQueryToolTest {

    private val json: Json = knowledgeQueryToolJson

    @Test
    fun `factory builds a FunctionTool with the canonical metadata`() {
        val store = InMemoryKnowledgeStore()
        val tool = KnowledgeQueryTool(store = store)

        assertEquals(KNOWLEDGE_QUERY_TOOL_ID, tool.id)
        assertEquals(KNOWLEDGE_QUERY_TOOL_NAME, tool.name)
        assertEquals(AgentActionAutonomy.FULLY_AUTONOMOUS, tool.requiredAgentAutonomy)
        assertTrue(tool.description.contains("knowledge"), "Description should mention knowledge")
        assertTrue(
            tool.description.contains("scope"),
            "Description should mention scope so plugin authors know it is gated",
        )
        assertIs<FunctionTool<*>>(tool)
    }

    @Test
    fun `factory threads pluginManifest through to the tool`() {
        val store = InMemoryKnowledgeStore()
        val manifest = manifest("pl-1", PluginPermission.KnowledgeQuery("work"))
        val tool = KnowledgeQueryTool(store = store, pluginManifest = manifest)

        assertNotNull(tool.pluginManifest)
        assertEquals("pl-1", tool.pluginManifest?.id)
    }

    @Test
    fun `factory accepts a custom autonomy level`() {
        val store = InMemoryKnowledgeStore()
        val tool = KnowledgeQueryTool(
            store = store,
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
        )
        assertEquals(AgentActionAutonomy.ASK_BEFORE_ACTION, tool.requiredAgentAutonomy)
    }

    @Test
    fun `tool execution returns ranked chunks for fixture documents`() = runTest {
        val store = InMemoryKnowledgeStore()
        store.addDocument(makeDocument("d1", "Lighthouses guide ships through fog.")).getOrThrow()
        store.addDocument(makeDocument("d2", "Beach sand erosion patterns.")).getOrThrow()
        store.chunkAndEmbed("d1", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()
        store.chunkAndEmbed("d2", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()

        val tool = KnowledgeQueryTool(store = store)
        val outcome = tool.execute(executionRequestFor(KnowledgeQueryRequest(text = "lighthouses")))

        val success = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        val response = json.decodeFromString(KnowledgeQueryResponse.serializer(), success.message)

        assertTrue(response.hits.isNotEmpty(), "Lighthouse query must return at least one hit")
        // Top hit should be the lighthouse-bearing document because pure-keyword wins.
        assertEquals("d1", response.hits.first().documentId)
        assertTrue(response.hits.first().text.contains("Lighthouses"))
    }

    @Test
    fun `tool execution surfaces source uri and scopes for each hit`() = runTest {
        val store = InMemoryKnowledgeStore()
        store.addDocument(
            KnowledgeDocument(
                id = "d1",
                title = "Lighthouse manual",
                sourceUri = "file:///docs/lighthouse.md",
                importedAt = Clock.System.now(),
                contentHash = "h-1",
                content = "Lighthouses guide ships.",
            ),
        ).getOrThrow()
        store.chunkAndEmbed("d1", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()
        store.setDocumentScopes("d1", setOf(KnowledgeScope.Work)).getOrThrow()

        val tool = KnowledgeQueryTool(store = store)
        val outcome = tool.execute(
            executionRequestFor(
                KnowledgeQueryRequest(
                    text = "lighthouses",
                    scopes = setOf(KnowledgeScope.Work),
                ),
            ),
        )

        val success = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        val response = json.decodeFromString(KnowledgeQueryResponse.serializer(), success.message)
        val hit = response.hits.single()
        assertEquals("file:///docs/lighthouse.md", hit.source)
        assertEquals(setOf(KnowledgeScope.Work), hit.scopes)
    }

    @Test
    fun `scope-filtered query returns only documents in the requested scope`() = runTest {
        val store = InMemoryKnowledgeStore()
        store.addDocument(makeDocument("work-doc", "Lighthouse work meeting.")).getOrThrow()
        store.addDocument(makeDocument("personal-doc", "Lighthouse vacation diary.")).getOrThrow()
        store.chunkAndEmbed("work-doc", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()
        store.chunkAndEmbed("personal-doc", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()
        store.setDocumentScopes("work-doc", setOf(KnowledgeScope.Work)).getOrThrow()
        store.setDocumentScopes("personal-doc", setOf(KnowledgeScope.Personal)).getOrThrow()

        val tool = KnowledgeQueryTool(store = store)
        val outcome = tool.execute(
            executionRequestFor(
                KnowledgeQueryRequest(
                    text = "lighthouse",
                    scopes = setOf(KnowledgeScope.Work),
                ),
            ),
        )

        val success = assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        val response = json.decodeFromString(KnowledgeQueryResponse.serializer(), success.message)
        assertEquals(listOf("work-doc"), response.hits.map { it.documentId })
    }

    @Test
    fun `permission gate denies when the matching scope grant is missing`() {
        val tool = KnowledgeQueryTool(
            store = InMemoryKnowledgeStore(),
            pluginManifest = manifest("pl-1", PluginPermission.KnowledgeQuery("work")),
        )

        val gateResult = PluginPermissionGate.check(
            toolCall = PluginToolCall(pluginId = "pl-1", toolId = tool.id),
            manifest = tool.pluginManifest!!,
            userGrants = UserGrants(),
        )

        assertEquals(
            GateResult.DenyMissing(PluginPermission.KnowledgeQuery("work")),
            gateResult,
        )
    }

    @Test
    fun `permission gate denies when the wrong scope is granted`() {
        val tool = KnowledgeQueryTool(
            store = InMemoryKnowledgeStore(),
            pluginManifest = manifest("pl-1", PluginPermission.KnowledgeQuery("work")),
        )

        val gateResult = PluginPermissionGate.check(
            toolCall = PluginToolCall(pluginId = "pl-1", toolId = tool.id),
            manifest = tool.pluginManifest!!,
            // User granted "personal" — not the "work" the manifest requires.
            userGrants = UserGrants.granted(PluginPermission.KnowledgeQuery("personal")),
        )

        assertEquals(
            GateResult.DenyMissing(PluginPermission.KnowledgeQuery("work")),
            gateResult,
        )
    }

    @Test
    fun `permission gate allows when the required scope is granted`() {
        val tool = KnowledgeQueryTool(
            store = InMemoryKnowledgeStore(),
            pluginManifest = manifest("pl-1", PluginPermission.KnowledgeQuery("work")),
        )

        val gateResult = PluginPermissionGate.check(
            toolCall = PluginToolCall(pluginId = "pl-1", toolId = tool.id),
            manifest = tool.pluginManifest!!,
            userGrants = UserGrants.granted(PluginPermission.KnowledgeQuery("work")),
        )

        assertEquals(GateResult.Allow, gateResult)
    }

    @Test
    fun `tool returns failure outcome when the request payload is malformed`() = runTest {
        val tool = KnowledgeQueryTool(store = InMemoryKnowledgeStore())
        val outcome = tool.execute(rawExecutionRequest("not valid json"))

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue(
            failure.message.contains("invalid request payload", ignoreCase = true),
            "Failure message should call out the payload error, got: ${failure.message}",
        )
    }

    @Test
    fun `executeKnowledgeQuery convenience returns ranked hits without JSON round trip`() =
        runTest {
            val store = InMemoryKnowledgeStore()
            store.addDocument(makeDocument("d1", "Lighthouses guide ships.")).getOrThrow()
            store.chunkAndEmbed("d1", InMemoryKnowledgeStore.DEFAULT_MODEL_ID).getOrThrow()

            val response = executeKnowledgeQuery(
                store = store,
                request = KnowledgeQueryRequest(
                    text = "lighthouses",
                    mode = QueryMode.KEYWORD,
                ),
            ).getOrThrow()

            assertEquals("d1", response.hits.single().documentId)
            assertNull(response.hits.single().source) // Document had no source URI.
        }

    private fun manifest(
        id: String,
        vararg permissions: PluginPermission,
    ): PluginManifest = PluginManifest(
        id = id,
        name = "Test plugin $id",
        version = "1.0.0",
        requiredPermissions = permissions.toList(),
    )

    private fun makeDocument(id: String, content: String): KnowledgeDocument =
        KnowledgeDocument(
            id = id,
            title = "Document $id",
            sourceUri = null,
            importedAt = Clock.System.now(),
            contentHash = "h-$id",
            content = content,
        )

    private fun executionRequestFor(
        request: KnowledgeQueryRequest,
    ): ExecutionRequest<ExecutionContext.NoChanges> = rawExecutionRequest(
        json.encodeToString(KnowledgeQueryRequest.serializer(), request),
    )

    private fun rawExecutionRequest(
        instructions: String,
    ): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        val ticket = Ticket(
            id = "ticket-1",
            title = "Ticket",
            description = "desc",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = "agent-1",
            createdByAgentId = "agent-1",
            createdAt = now,
            updatedAt = now,
        )
        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Run knowledge query",
        )
        val context = ExecutionContext.NoChanges(
            executorId = "executor-1",
            ticket = ticket,
            task = task,
            instructions = instructions,
        )
        return ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )
    }
}
