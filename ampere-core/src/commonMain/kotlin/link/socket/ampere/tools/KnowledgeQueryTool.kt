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
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission

/**
 * Plug-callable knowledge query primitive (W2.3 / AMPR-156).
 *
 * Wraps the on-device [KnowledgeStore] as a [FunctionTool] that plugs may
 * invoke to retrieve ranked chunks for a free-form query. The [PlugManifest]
 * threaded through here is the same manifest the
 * [PlugPermissionGate][link.socket.ampere.plug.permission.PlugPermissionGate]
 * checks before the
 * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]
 * dispatches the tool, so the gate can deny on a missing
 * [PlugPermission.KnowledgeQuery][link.socket.ampere.plug.permission.PlugPermission.KnowledgeQuery]
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
 * Keeps just the fields a plug caller actually needs — the underlying
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
 * Direct typed entry point used by tests and by internal callers that
 * already have a typed [KnowledgeQueryRequest] and are not plug-originated.
 * Mirrors [KnowledgeStore.query]'s contract verbatim: empty
 * [KnowledgeQueryRequest.scopes] disables scope filtering rather than
 * resolving to any particular grant.
 *
 * Plug-originated calls dispatch through the [KnowledgeQueryTool] factory's
 * `executionFunction` instead, which resolves empty scopes to the plug's
 * granted scopes and denies scopes the plug was not granted — see
 * [grantedKnowledgeScopes].
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
 * Build a [FunctionTool] that exposes [KnowledgeStore.query] to plugs.
 *
 * Hybrid scoring lives inside the store
 * ([HybridQueryRanker][link.socket.ampere.knowledge.HybridQueryRanker]) so
 * the tool stays a thin permission-gated facade.
 *
 * @param store The on-device knowledge store. Same instance per plug.
 * @param plugManifest Manifest of the plug that owns this tool. Required —
 *        a plug-callable knowledge query has no meaning without a manifest to
 *        gate scopes against, so there is no manifest-less overload. A tool
 *        that bypasses gating entirely should call [executeKnowledgeQuery]
 *        directly instead of going through this factory. The manifest's
 *        `requiredPermissions`
 *        [PlugPermission.KnowledgeQuery][link.socket.ampere.plug.permission.PlugPermission.KnowledgeQuery]
 *        entries define the plug's granted scopes: a request naming any
 *        other scope is denied, and an empty request resolves to exactly
 *        this set rather than to "no filter".
 * @param requiredAgentAutonomy Minimum autonomy level. Defaults to
 *        [AgentActionAutonomy.FULLY_AUTONOMOUS] because the tool reads only
 *        and the permission gate enforces scope.
 */
@Suppress("FunctionName")
fun KnowledgeQueryTool(
    store: KnowledgeStore,
    plugManifest: PlugManifest,
    requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
    json: Json = knowledgeQueryToolJson,
): FunctionTool<ExecutionContext.NoChanges> {
    return FunctionTool(
        id = ID,
        name = NAME,
        description = DESCRIPTION,
        requiredAgentAutonomy = requiredAgentAutonomy,
        plugManifest = plugManifest,
        executionFunction = { executionRequest ->
            executeAsOutcome(
                store = store,
                plugManifest = plugManifest,
                executionRequest = executionRequest,
                json = json,
            )
        },
    )
}

private suspend fun executeAsOutcome(
    store: KnowledgeStore,
    plugManifest: PlugManifest,
    executionRequest: ExecutionRequest<ExecutionContext.NoChanges>,
    json: Json,
): ExecutionOutcome.NoChanges {
    val context = executionRequest.context
    val startTimestamp = Clock.System.now()

    fun failure(message: String) = ExecutionOutcome.NoChanges.Failure(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = startTimestamp,
        executionEndTimestamp = Clock.System.now(),
        message = message,
    )

    fun success(response: KnowledgeQueryResponse) = ExecutionOutcome.NoChanges.Success(
        executorId = context.executorId,
        ticketId = context.ticket.id,
        taskId = context.task.id,
        executionStartTimestamp = startTimestamp,
        executionEndTimestamp = Clock.System.now(),
        message = json.encodeToString(KnowledgeQueryResponse.serializer(), response),
    )

    val request = runCatching {
        json.decodeFromString(KnowledgeQueryRequest.serializer(), context.instructions)
    }.getOrElse { error ->
        return failure("knowledge_query: invalid request payload — ${error.message}")
    }

    val grantedScopes = plugManifest.grantedKnowledgeScopes()
    val deniedScopes = request.scopes - grantedScopes
    if (deniedScopes.isNotEmpty()) {
        return failure(
            "knowledge_query: plug '${plugManifest.id.value}' was not granted scope(s) " +
                deniedScopes.joinToString { it.name },
        )
    }

    // An empty request resolves to the plug's granted scopes rather than to
    // KnowledgeStore.query's own "no filter" default — see grantedKnowledgeScopes.
    if (request.scopes.isEmpty() && grantedScopes.isEmpty()) {
        return success(KnowledgeQueryResponse(hits = emptyList()))
    }
    val gatedRequest = request.copy(scopes = request.scopes.ifEmpty { grantedScopes })

    return executeKnowledgeQuery(store, gatedRequest).fold(
        onSuccess = { response -> success(response) },
        onFailure = { error -> failure("knowledge_query: store query failed — ${error.message}") },
    )
}

/**
 * The [KnowledgeScope]s [this] manifest's declared
 * [PlugPermission.KnowledgeQuery] entries authorize.
 *
 * [link.socket.ampere.plug.permission.PlugPermissionGate] already requires
 * every one of [PlugManifest.requiredPermissions] to be granted (and not
 * revoked) before any of this plug's tools dispatch, so by the time a
 * [KnowledgeQueryTool] call reaches [executeAsOutcome] this set is
 * guaranteed to be the plug's actually-granted scopes, not merely its
 * declared ones.
 */
private fun PlugManifest.grantedKnowledgeScopes(): Set<KnowledgeScope> =
    requiredPermissions
        .filterIsInstance<PlugPermission.KnowledgeQuery>()
        .mapTo(mutableSetOf()) { KnowledgeScope(it.scope) }

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
        "Scope-restricted plugs must hold the matching KnowledgeQuery permission grant."
