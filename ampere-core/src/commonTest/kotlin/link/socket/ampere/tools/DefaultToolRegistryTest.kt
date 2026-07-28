package link.socket.ampere.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.knowledge.InMemoryKnowledgeStore
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.GateResult
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.PlugPermissionGate
import link.socket.ampere.plug.permission.PlugToolCall
import link.socket.ampere.plug.permission.UserGrants

class DefaultToolRegistryTest {

    @Test
    fun `default plug tool list contains the knowledge query tool`() {
        val tools = DefaultToolRegistry.createDefaultPlugTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest("pl-1", PlugPermission.KnowledgeQuery("work")),
        )

        val tool = tools.single()
        assertEquals(KNOWLEDGE_QUERY_TOOL_ID, tool.id)
        assertIs<FunctionTool<*>>(tool)
    }

    @Test
    fun `default tool stamps the manifest so the gate can attribute calls`() {
        val manifest = manifest("pl-1", PlugPermission.KnowledgeQuery("work"))
        val tool = DefaultToolRegistry.createDefaultPlugTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest,
        ).single()

        assertNotNull(tool.plugManifest)
        assertEquals(manifest.id, tool.plugManifest?.id)
        assertEquals(
            listOf(PlugPermission.KnowledgeQuery("work")),
            tool.plugManifest?.requiredPermissions,
        )
    }

    @Test
    fun `default registry honours the requested autonomy override`() {
        val tool = DefaultToolRegistry.createDefaultPlugTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest("pl-1"),
            requiredAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
        ).single()

        assertEquals(AgentActionAutonomy.ASK_BEFORE_ACTION, tool.requiredAgentAutonomy)
    }

    @Test
    fun `gate denies a missing scope grant for a tool from the default registry`() {
        val manifest = manifest("pl-1", PlugPermission.KnowledgeQuery("work"))
        val tool = DefaultToolRegistry.createDefaultPlugTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest,
        ).single()

        val gateResult = PlugPermissionGate.check(
            toolCall = PlugToolCall(plugId = manifest.id, toolId = tool.id),
            manifest = manifest,
            userGrants = UserGrants(),
        )

        assertEquals(
            GateResult.DenyMissing(PlugPermission.KnowledgeQuery("work")),
            gateResult,
        )
    }

    @Test
    fun `gate allows when the matching scope grant is present`() {
        val manifest = manifest("pl-1", PlugPermission.KnowledgeQuery("work"))
        val tool = DefaultToolRegistry.createDefaultPlugTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest,
        ).single()

        val gateResult = PlugPermissionGate.check(
            toolCall = PlugToolCall(plugId = manifest.id, toolId = tool.id),
            manifest = manifest,
            userGrants = UserGrants.granted(PlugPermission.KnowledgeQuery("work")),
        )

        assertEquals(GateResult.Allow, gateResult)
    }

    private fun manifest(
        id: String,
        vararg permissions: PlugPermission,
    ): PlugManifest = PlugManifest(
        id = id,
        name = "Test plug $id",
        version = "1.0.0",
        requiredPermissions = permissions.toList(),
    )
}
