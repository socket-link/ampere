package link.socket.ampere.link

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PersistedStore
import link.socket.ampere.agents.domain.event.StoreRowUndecodableEvent
import link.socket.ampere.agents.events.UndecodableRow
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.db.Database
import link.socket.ampere.plug.PlugId
import link.socket.ampere.util.ioDispatcher

/**
 * Persistence boundary for [Link]s and the per-Plug grants on them.
 *
 * All fallible operations return [Result]; no exceptions cross this boundary.
 * Agents never touch the database — they reach Links through
 * [LinkResolutionService], which sits on top of this.
 */
interface LinkStore {

    suspend fun upsert(link: Link, updatedAt: Instant = Clock.System.now()): Result<Unit>

    /**
     * The Link with this id, null if there is no such row, or a
     * [UndecodableLinkException] failure if the row exists and this build cannot decode it
     * (AMPR-364).
     */
    suspend fun get(linkId: LinkId): Result<Link?>

    /**
     * Every stored Link this build can decode.
     *
     * A row that will not decode is skipped rather than failing the query (AMPR-364): one Link
     * naming a canon member this build does not have used to poison the whole list, and
     * [LinkResolutionService] reads a failed list as "no Links at all" — so one unreadable row
     * silently unplugged every Plug. Skips are announced, not swallowed; see
     * [SqlDelightLinkStore].
     */
    suspend fun list(): Result<List<Link>>

    /** As [list], narrowed by [transport], with the same skip-rather-than-fail behaviour. */
    suspend fun listByTransport(transport: Transport): Result<List<Link>>

    suspend fun delete(linkId: LinkId): Result<Unit>

    suspend fun grant(
        plugId: PlugId,
        linkId: LinkId,
        grantedAt: Instant = Clock.System.now(),
    ): Result<Unit>

    suspend fun revokeGrant(
        plugId: PlugId,
        linkId: LinkId,
        revokedAt: Instant = Clock.System.now(),
    ): Result<Unit>

    suspend fun grantsForPlug(plugId: PlugId): Result<LinkGrants>

    suspend fun grantsForLink(linkId: LinkId): Result<List<LinkGrant>>

    /**
     * Revoke the Link itself and cascade to every grant on it.
     *
     * Returns the plug ids that lost access, so the caller can report the true
     * blast radius rather than a single row.
     */
    suspend fun revokeLink(
        linkId: LinkId,
        revokedAt: Instant = Clock.System.now(),
    ): Result<List<String>>
}

/**
 * The persistent [LinkStore].
 *
 * @param eventApi Optional door. With one, every row [list] and [listByTransport] had to skip is
 *   announced as a [StoreRowUndecodableEvent] — the glass-brain rule applies to a dropped row as
 *   much as to a dropped event. Without it the store still degrades rather than failing; the skip
 *   just goes unobserved, exactly as [LinkResolutionService] behaves without a door.
 */
class SqlDelightLinkStore(
    private val database: Database,
    private val eventApi: AgentEventApi? = null,
    private val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : LinkStore {

    private val queries
        get() = database.linksQueries

    /**
     * Rows already announced as undecodable, and the lock over it.
     *
     * The row stays undecodable until something rewrites it, and [list] runs on every Plug
     * resolution, so announcing per read would turn one bad row into an unbounded stream of
     * events about it. Said once per store, then quiet.
     */
    private val reportedUndecodableRows = mutableSetOf<String>()
    private val undecodableReportLock = Mutex()

    override suspend fun upsert(link: Link, updatedAt: Instant): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                queries.upsertLink(
                    link_id = link.id.value,
                    transport = link.transport.name,
                    link_json = encode(link),
                    updated_at = updatedAt.toEpochMilliseconds(),
                )
            }.map { }
        }

    override suspend fun get(linkId: LinkId): Result<Link?> =
        withContext(ioDispatcher) {
            runCatching {
                queries.selectLink(linkId.value).executeAsOneOrNull()?.let { payload ->
                    decodeOrFail(linkId, payload)
                }
            }
        }

    override suspend fun list(): Result<List<Link>> =
        decodeRows { queries.selectAllLinks(::StoredLinkRow).executeAsList() }

    override suspend fun listByTransport(transport: Transport): Result<List<Link>> =
        decodeRows { queries.selectLinksByTransport(transport.name, ::StoredLinkRow).executeAsList() }

    override suspend fun delete(linkId: LinkId): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                queries.deleteGrantsForLink(linkId.value)
                queries.deleteLink(linkId.value)
            }.map { }
        }

    override suspend fun grant(
        plugId: PlugId,
        linkId: LinkId,
        grantedAt: Instant,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                queries.upsertLinkGrant(
                    plug_id = plugId.value,
                    link_id = linkId.value,
                    granted_at = grantedAt.toEpochMilliseconds(),
                    revoked_at = null,
                )
            }.map { }
        }

    override suspend fun revokeGrant(
        plugId: PlugId,
        linkId: LinkId,
        revokedAt: Instant,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val existing = queries.selectGrantsForPlug(plugId.value)
                    .executeAsList()
                    .firstOrNull { it.link_id == linkId.value }

                queries.upsertLinkGrant(
                    plug_id = plugId.value,
                    link_id = linkId.value,
                    granted_at = existing?.granted_at ?: revokedAt.toEpochMilliseconds(),
                    revoked_at = revokedAt.toEpochMilliseconds(),
                )
            }.map { }
        }

    override suspend fun grantsForPlug(plugId: PlugId): Result<LinkGrants> =
        withContext(ioDispatcher) {
            runCatching {
                LinkGrants(
                    plugId = plugId,
                    grants = queries.selectGrantsForPlug(plugId.value).executeAsList().map { row ->
                        LinkGrant(
                            plugId = PlugId(row.plug_id),
                            linkId = LinkId(row.link_id),
                            grantedAt = Instant.fromEpochMilliseconds(row.granted_at),
                            revokedAt = row.revoked_at?.let(Instant::fromEpochMilliseconds),
                        )
                    },
                )
            }
        }

    override suspend fun grantsForLink(linkId: LinkId): Result<List<LinkGrant>> =
        withContext(ioDispatcher) {
            runCatching {
                queries.selectGrantsForLink(linkId.value).executeAsList().map { row ->
                    LinkGrant(
                        plugId = PlugId(row.plug_id),
                        linkId = LinkId(row.link_id),
                        grantedAt = Instant.fromEpochMilliseconds(row.granted_at),
                        revokedAt = row.revoked_at?.let(Instant::fromEpochMilliseconds),
                    )
                }
            }
        }

    override suspend fun revokeLink(
        linkId: LinkId,
        revokedAt: Instant,
    ): Result<List<String>> =
        withContext(ioDispatcher) {
            runCatching {
                val affected = queries.selectGrantsForLink(linkId.value)
                    .executeAsList()
                    .filter { it.revoked_at == null }
                    .map { it.plug_id }

                queries.revokeGrantsForLink(
                    revoked_at = revokedAt.toEpochMilliseconds(),
                    link_id = linkId.value,
                )

                queries.selectLink(linkId.value).executeAsOneOrNull()?.let { stored ->
                    val revoked = decodeOrFail(linkId, stored).copy(revokedAt = revokedAt)
                    queries.upsertLink(
                        link_id = revoked.id.value,
                        transport = revoked.transport.name,
                        link_json = encode(revoked),
                        updated_at = revokedAt.toEpochMilliseconds(),
                    )
                }

                affected
            }
        }

    /**
     * Read and decode rows on [ioDispatcher], skipping every one this build cannot read, then
     * announce the skips (AMPR-364).
     *
     * A decode failure is caught per row rather than by the surrounding `runCatching`, which is
     * the whole change: one bad row must not become the query's failure. The announcement comes
     * after, outside the `Result`, so a publish through the door cannot fail the read either.
     */
    private suspend fun decodeRows(query: () -> List<StoredLinkRow>): Result<List<Link>> {
        val skipped = mutableListOf<UndecodableRow>()

        val links = withContext(ioDispatcher) {
            runCatching {
                query().mapNotNull { row ->
                    try {
                        decode(row.linkJson)
                    } catch (throwable: Throwable) {
                        skipped += UndecodableRow(row.linkId, throwable.reasonText())
                        null
                    }
                }
            }
        }.getOrElse { throwable -> return Result.failure(throwable) }

        skipped.forEach { row -> announceUndecodable(row) }

        return Result.success(links)
    }

    /**
     * Say on the bus that a row was skipped, once per row id. No door, no event — and no throw
     * either: a store with no observer still degrades rather than failing.
     *
     * The row is marked as reported only once the publish succeeded, so a write the store refused
     * leaves it unreported and the next read says it again. Silence is the one outcome this must
     * not produce.
     */
    private suspend fun announceUndecodable(row: UndecodableRow) {
        val api = eventApi ?: return
        val reported = undecodableReportLock.withLock { row.rowId in reportedUndecodableRows }
        if (reported) return

        api.publish(
            StoreRowUndecodableEvent(
                eventId = generateUUID("store-row-undecodable", api.agentId),
                timestamp = api.clock.now(),
                eventSource = EventSource.Agent(api.agentId),
                store = PersistedStore.LINK_STORE,
                rowId = row.rowId,
                reason = row.reason,
            ),
        ).onSuccess {
            undecodableReportLock.withLock { reportedUndecodableRows += row.rowId }
        }
    }

    /**
     * Decode one row for a caller that named it, or throw [UndecodableLinkException] for the
     * enclosing `runCatching` to turn into a typed [Result.failure]. A single-row read has no
     * rest of the query to save, so the caller gets told rather than handed a silent null.
     */
    private fun decodeOrFail(linkId: LinkId, payload: String): Link =
        try {
            decode(payload)
        } catch (throwable: Throwable) {
            throw UndecodableLinkException(linkId, throwable.reasonText(), throwable)
        }

    private fun Throwable.reasonText(): String = message ?: this::class.simpleName.orEmpty()

    private fun encode(link: Link): String = json.encodeToString(Link.serializer(), link)

    private fun decode(payload: String): Link = json.decodeFromString(Link.serializer(), payload)

    /** One `Links` row as the list queries read it: the id, and the payload that names it. */
    private class StoredLinkRow(
        val linkId: String,
        val linkJson: String,
    )
}

/**
 * In-memory [LinkStore] for tests and single-process environments.
 *
 * Mirrors [link.socket.ampere.mcp.InMemoryMcpCredentialBinding]: the persistent
 * implementation is the real one, this exists so resolution can be exercised
 * without a driver.
 */
class InMemoryLinkStore(
    links: List<Link> = emptyList(),
    grants: List<LinkGrant> = emptyList(),
) : LinkStore {

    private val links = links.associateBy { it.id }.toMutableMap()
    private val grants: MutableMap<Pair<PlugId, LinkId>, LinkGrant> =
        grants.associateBy { it.plugId to it.linkId }.toMutableMap()

    override suspend fun upsert(link: Link, updatedAt: Instant): Result<Unit> {
        links[link.id] = link
        return Result.success(Unit)
    }

    override suspend fun get(linkId: LinkId): Result<Link?> = Result.success(links[linkId])

    override suspend fun list(): Result<List<Link>> = Result.success(links.values.toList())

    override suspend fun listByTransport(transport: Transport): Result<List<Link>> =
        Result.success(links.values.filter { it.transport == transport })

    override suspend fun delete(linkId: LinkId): Result<Unit> {
        links.remove(linkId)
        grants.keys.filter { it.second == linkId }.forEach(grants::remove)
        return Result.success(Unit)
    }

    override suspend fun grant(
        plugId: PlugId,
        linkId: LinkId,
        grantedAt: Instant,
    ): Result<Unit> {
        grants[plugId to linkId] = LinkGrant(plugId, linkId, grantedAt)
        return Result.success(Unit)
    }

    override suspend fun revokeGrant(
        plugId: PlugId,
        linkId: LinkId,
        revokedAt: Instant,
    ): Result<Unit> {
        val existing = grants[plugId to linkId]
        grants[plugId to linkId] = LinkGrant(
            plugId = plugId,
            linkId = linkId,
            grantedAt = existing?.grantedAt ?: revokedAt,
            revokedAt = revokedAt,
        )
        return Result.success(Unit)
    }

    override suspend fun grantsForPlug(plugId: PlugId): Result<LinkGrants> =
        Result.success(
            LinkGrants(plugId, grants.values.filter { it.plugId == plugId }),
        )

    override suspend fun grantsForLink(linkId: LinkId): Result<List<LinkGrant>> =
        Result.success(grants.values.filter { it.linkId == linkId })

    override suspend fun revokeLink(linkId: LinkId, revokedAt: Instant): Result<List<String>> {
        val affected = grants.values
            .filter { it.linkId == linkId && !it.isRevoked }
            .map { it.plugId }

        affected.forEach { plugId ->
            grants[plugId to linkId] = grants.getValue(plugId to linkId).copy(revokedAt = revokedAt)
        }

        links[linkId]?.let { links[linkId] = it.copy(revokedAt = revokedAt) }

        return Result.success(affected.map { it.value })
    }
}
