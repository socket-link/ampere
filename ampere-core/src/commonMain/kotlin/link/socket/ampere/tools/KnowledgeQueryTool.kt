package link.socket.ampere.tools

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.knowledge.KnowledgeQueryResult
import link.socket.ampere.knowledge.KnowledgeScope
import link.socket.ampere.knowledge.KnowledgeStore
import link.socket.ampere.knowledge.QueryMode
import link.socket.ampere.plugin.PluginManifest

/**
 * Plugin-callable knowledge query primitive (W2.3 / AMPR-156).
 *
 * Wraps the on-device [KnowledgeStore] as a [FunctionTool] that plugins may
 * invoke to retrieve ranked chunks for a free-form query. The [PluginManifest]
 * threaded through here is the same manifest the
 * [PluginPermissionGate][link.socket.ampere.plugin.permission.PluginPermissionGate]
 * checks before the
 * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]
 * dispatches the tool, so the gate can deny on a missing
 * [PluginPermission.KnowledgeQuery][link.socket.ampere.plugin.permission.PluginPermission.KnowledgeQuery]
 * grant before any store I/O.
 *
 * Inputs ([KnowledgeQueryRequest]) and outputs ([KnowledgeQueryResponse])
 * round-trip through [ExecutionRequest.context]'s `instructions` and the
 * resulting [ExecutionOutcome.NoChanges.Success.message] as JSON, which is
 * how the [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]
 * already wires LLM-generated parameters and tool replies. Callers that
 * already have a typed request should call [executeKnowledgeQuery] directly
 * to skip the JSON round-trip.
 */
@Serializable
data class KnowledgeQueryRequest(
    val text: String,
    val scopes: Set<KnowledgeScope> = emptySet(),
    val limit: Int = KnowledgeStore.DEFAULT_QUERY_LIMIT,
    val mode: QueryMode = QueryMode.HYBRID,
)

/**
 * One ranked chunk returned by [KnowledgeQueryTool].
 *
 * Keeps just the fields a plugin caller actually needs — the underlying
 * [KnowledgeQueryResult] is not exposed verbatim because [KnowledgeQueryResult]
 * is intentionally store-shaped (it carries the full chunk record).
 */
@Serializable
data class KnowledgeQueryHit(
    val chunkId: String,
    val documentId: String,
    val text: String,
    val source: String?,
    val score: Float,
    val scopes: Set<KnowledgeScope> = emptySet(),
)

@Serializable
data class KnowledgeQueryResponse(
    val hits: List<KnowledgeQueryHit>,
)

/**
 * Stable JSON encoder shared between [KnowledgeQueryTool] invocations.
 *
 * Uses `ignoreUnknownKeys = true` so a future request schema addition does
 * not break tools that already encoded older requests.
 */
internal val knowledgeQueryToolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Direct typed entry point used by tests and by callers that already have a
 * typed [KnowledgeQueryRequest]. The tool factory delegates to this after
 * decoding the request from JSON.
 */
suspend fun executeKnowledgeQuery(
    store: KnowledgeStore,
    request: KnowledgeQueryRequest,
): Result<KnowledgeQueryResponse> {
    return store.query(
        text = request.text,
        limit = request.limit,
        mode = request.mode,
        scopes = request.scopes,
    ).map { results ->
        KnowledgeQueryResponse(
            hits = results.map { it.toHit() },
        )
    }
}

/**
 * Build a [FunctionTool] that exposes [KnowledgeStore.query] to plugins.
 *
 * Hybrid scoring lives inside the store
 * ([HybridQueryRanker][link.socket.ampere.knowledge.HybridQueryRanker]) so
 * the tool stays a thin permission-gated facade.
 *
 * @param store The on-device knowledge store. Same instance per plugin.
 * @param pluginManifest Manifest of the plugin that owns this tool. The
 *        manifest's `requiredPermissions` should include at least one
 *        [PluginPermission.KnowledgeQuery][link.socket.ampere.plugin.permission.PluginPermission.KnowledgeQuery]
 *        so the gate can match the requested scope.
 * @param requiredAgentAutonomy Minimum autonomy level. Defaults to
 *        [AgentActionAutonomy.FULLY_AUTONOMOUS] because the tool reads only
 *        and the permission gate enforces scope.
 */
@Suppress("FunctionName")
fun KnowledgeQueryTool(
    store: KnowledgeStore,
    pluginManifest: PluginManifest? = null,
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    json: Json = knowledgeQueryToolJson,
): FunctionTool<ExecutionContext.NoChanges> {
    return FunctionTool(
        id = ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        pluginManifest = pluginManifest,
        executionFunction = { executionRequest ->
            executeAsOutcome(
                store = store,
                executionRequest = executionRequest,
                json = json,
            )
        },
    )
}

private suspend fun executeAsOutcome(
    store: KnowledgeStore,
    executionRequest: ExecutionRequest<ExecutionContext.NoChanges>,
    json: Json,
): ExecutionOutcome.NoChanges {
    val context = executionRequest.context
    val startTimestamp = Clock.System.now()

    val request = runCatching {
        json.decodeFromString(KnowledgeQueryRequest.serializer(), context.instructions)
    }.getOrElse { error ->
        return ExecutionOutcome.NoChanges.Failure(
            executorId = context.executorId,
            ticketId = context.ticket.id,
            taskId = context.task.id,
            executionStartTimestamp = startTimestamp,
            executionEndTimestamp = Clock.System.now(),
            message = "knowledge_query: invalid request payload — ${error.message}",
        )
    }

    return executeKnowledgeQuery(store, request).fold(
        onSuccess = { response ->
            ExecutionOutcome.NoChanges.Success(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = startTimestamp,
                executionEndTimestamp = Clock.System.now(),
                message = json.encodeToString(KnowledgeQueryResponse.serializer(), response),
            )
        },
        onFailure = { error ->
            ExecutionOutcome.NoChanges.Failure(
                executorId = context.executorId,
                ticketId = context.ticket.id,
                taskId = context.task.id,
                executionStartTimestamp = startTimestamp,
                executionEndTimestamp = Clock.System.now(),
                message = "knowledge_query: store query failed — ${error.message}",
            )
        },
    )
}

private fun KnowledgeQueryResult.toHit(): KnowledgeQueryHit =
    KnowledgeQueryHit(
        chunkId = chunk.id,
        documentId = chunk.documentId,
        text = chunk.text,
        source = sourceUri,
        score = score,
        scopes = scopes,
    )

const val KNOWLEDGE_QUERY_TOOL_ID: String = "knowledge_query"
const val KNOWLEDGE_QUERY_TOOL_NAME: String = "Query Knowledge"

private const val ID = KNOWLEDGE_QUERY_TOOL_ID
private const val NAME = KNOWLEDGE_QUERY_TOOL_NAME
private const val DESCRIPTION =
    "Searches the on-device knowledge store for chunks relevant to a query. " +
        "Inputs: query text, optional scope set (e.g., 'work', 'personal'), and a result limit. " +
        "Output: ranked chunks with text, source URI, and similarity score. " +
        "Scope-restricted plugins must hold the matching KnowledgeQuery permission grant."
