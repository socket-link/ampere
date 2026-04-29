package link.socket.ampere.tools

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.knowledge.KnowledgeStore
import link.socket.ampere.plugin.PluginManifest

/**
 * Factory for the AMPERE default Plugin tool registry (W2.3 / AMPR-156).
 *
 * Plugins ship a [PluginManifest] declaring the permissions they require
 * (W0.1). When a plugin loads, the host calls [createDefaultPluginTools] with
 * the manifest and the on-device [KnowledgeStore] to build the tool list the
 * plugin can actually invoke. Each tool carries the manifest, so the
 * [PluginPermissionGate][link.socket.ampere.plugin.permission.PluginPermissionGate]
 * inside
 * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]
 * checks
 * [PluginPermission.KnowledgeQuery][link.socket.ampere.plugin.permission.PluginPermission.KnowledgeQuery]
 * against the user's grants before dispatching anything.
 *
 * The registry is intentionally minimal in this ticket — only the knowledge
 * query primitive is wired in. Future plugin-callable primitives (native
 * actions, link access, etc.) plug in here as new factory entries.
 */
object DefaultToolRegistry {

    /**
     * Build the default tool list for the plugin described by [manifest].
     *
     * @param store The on-device knowledge store the plugin will query.
     * @param manifest The plugin's manifest. Tools are stamped with this
     *        manifest so the permission gate can attribute and enforce.
     * @param requiredAutonomy Minimum agent autonomy level for the bundled
     *        knowledge query tool. Defaults to
     *        [AgentActionAutonomy.FULLY_AUTONOMOUS] (read-only, gated by
     *        scope grants).
     */
    fun createDefaultPluginTools(
        store: KnowledgeStore,
        manifest: PluginManifest,
        requiredAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    ): List<Tool<*>> {
        return listOf(
            KnowledgeQueryTool(
                store = store,
                pluginManifest = manifest,
                requiredAgentAutonomy = requiredAutonomy,
            ),
        )
    }
}
