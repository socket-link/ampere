package link.socket.ampere.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.knowledge.InMemoryKnowledgeStore
import link.socket.ampere.plugin.PluginManifest
import link.socket.ampere.plugin.permission.GateResult
import link.socket.ampere.plugin.permission.PluginPermission
import link.socket.ampere.plugin.permission.PluginPermissionGate
import link.socket.ampere.plugin.permission.PluginToolCall
import link.socket.ampere.plugin.permission.UserGrants

class DefaultToolRegistryTest {

    @Test
    fun `default plugin tool list contains the knowledge query tool`() {
        val tools = DefaultToolRegistry.createDefaultPluginTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest("pl-1", PluginPermission.KnowledgeQuery("work")),
        )

        val tool = tools.single()
        assertEquals(KNOWLEDGE_QUERY_TOOL_ID, tool.id)
        assertIs<FunctionTool<*>>(tool)
    }

    @Test
    fun `default tool stamps the manifest so the gate can attribute calls`() {
        val manifest = manifest("pl-1", PluginPermission.KnowledgeQuery("work"))
        val tool = DefaultToolRegistry.createDefaultPluginTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest,
        ).single()

        assertNotNull(tool.pluginManifest)
        assertEquals(manifest.id, tool.pluginManifest?.id)
        assertEquals(
            listOf(PluginPermission.KnowledgeQuery("work")),
            tool.pluginManifest?.requiredPermissions,
        )
    }

    @Test
    fun `default registry honours the requested autonomy override`() {
        val tool = DefaultToolRegistry.createDefaultPluginTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest("pl-1"),
            requiredAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
        ).single()

        assertEquals(AgentActionAutonomy.ASK_BEFORE_ACTION, tool.requiredAgentAutonomy)
    }

    @Test
    fun `gate denies a missing scope grant for a tool from the default registry`() {
        val manifest = manifest("pl-1", PluginPermission.KnowledgeQuery("work"))
        val tool = DefaultToolRegistry.createDefaultPluginTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest,
        ).single()

        val gateResult = PluginPermissionGate.check(
            toolCall = PluginToolCall(pluginId = manifest.id, toolId = tool.id),
            manifest = manifest,
            userGrants = UserGrants(),
        )

        assertEquals(
            GateResult.DenyMissing(PluginPermission.KnowledgeQuery("work")),
            gateResult,
        )
    }

    @Test
    fun `gate allows when the matching scope grant is present`() {
        val manifest = manifest("pl-1", PluginPermission.KnowledgeQuery("work"))
        val tool = DefaultToolRegistry.createDefaultPluginTools(
            store = InMemoryKnowledgeStore(),
            manifest = manifest,
        ).single()

        val gateResult = PluginPermissionGate.check(
            toolCall = PluginToolCall(pluginId = manifest.id, toolId = tool.id),
            manifest = manifest,
            userGrants = UserGrants.granted(PluginPermission.KnowledgeQuery("work")),
        )

        assertEquals(GateResult.Allow, gateResult)
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
}
