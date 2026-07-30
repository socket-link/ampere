package link.socket.ampere.link

import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
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

    suspend fun get(linkId: LinkId): Result<Link?>

    suspend fun list(): Result<List<Link>>

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

class SqlDelightLinkStore(
    private val database: Database,
    private val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : LinkStore {

    private val queries
        get() = database.linksQueries

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
                queries.selectLink(linkId.value).executeAsOneOrNull()?.let(::decode)
            }
        }

    override suspend fun list(): Result<List<Link>> =
        withContext(ioDispatcher) {
            runCatching { queries.selectAllLinks().executeAsList().map(::decode) }
        }

    override suspend fun listByTransport(transport: Transport): Result<List<Link>> =
        withContext(ioDispatcher) {
            runCatching {
                queries.selectLinksByTransport(transport.name).executeAsList().map(::decode)
            }
        }

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
                    val revoked = decode(stored).copy(revokedAt = revokedAt)
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

    private fun encode(link: Link): String = json.encodeToString(Link.serializer(), link)

    private fun decode(payload: String): Link = json.decodeFromString(Link.serializer(), payload)
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
