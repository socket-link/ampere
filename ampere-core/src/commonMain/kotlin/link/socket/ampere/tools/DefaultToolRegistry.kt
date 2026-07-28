package link.socket.ampere.tools

import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.knowledge.KnowledgeStore
import link.socket.ampere.plug.PlugManifest

/**
 * Factory for the AMPERE default Plug tool registry (W2.3 / AMPR-156).
 *
 * Plugs ship a [PlugManifest] declaring the permissions they require
 * (W0.1). When a plug loads, the host calls [createDefaultPlugTools] with
 * the manifest and the on-device [KnowledgeStore] to build the tool list the
 * plug can actually invoke. Each tool carries the manifest, so the
 * [PlugPermissionGate][link.socket.ampere.plug.permission.PlugPermissionGate]
 * inside
 * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]
 * checks
 * [PlugPermission.KnowledgeQuery][link.socket.ampere.plug.permission.PlugPermission.KnowledgeQuery]
 * against the user's grants before dispatching anything.
 *
 * The registry is intentionally minimal in this ticket — only the knowledge
 * query primitive is wired in. Future plug-callable primitives (native
 * actions, link access, etc.) plug in here as new factory entries.
 */
object DefaultToolRegistry {

    /**
     * Build the default tool list for the plug described by [manifest].
     *
     * @param store The on-device knowledge store the plug will query.
     * @param manifest The plug's manifest. Tools are stamped with this
     *        manifest so the permission gate can attribute and enforce.
     * @param requiredAutonomy Minimum agent autonomy level for the bundled
     *        knowledge query tool. Defaults to
     *        [AgentActionAutonomy.FULLY_AUTONOMOUS] (read-only, gated by
     *        scope grants).
     */
    fun createDefaultPlugTools(
        store: KnowledgeStore,
        manifest: PlugManifest,
        requiredAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    ): List<Tool<*>> {
        return listOf(
            KnowledgeQueryTool(
                store = store,
                plugManifest = manifest,
                requiredAgentAutonomy = requiredAutonomy,
            ),
        )
    }
}
