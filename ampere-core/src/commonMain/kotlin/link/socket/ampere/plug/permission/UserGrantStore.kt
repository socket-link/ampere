package link.socket.ampere.plug.permission

import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.db.Database
import link.socket.ampere.plug.PlugId
import link.socket.ampere.util.ioDispatcher

interface UserGrantStore {

    suspend fun grant(
        plugId: PlugId,
        permission: PlugPermission,
        grantedAt: Instant = Clock.System.now(),
    ): Result<Unit>

    suspend fun revoke(
        plugId: PlugId,
        permission: PlugPermission,
    ): Result<Unit>

    suspend fun listGrants(plugId: PlugId): Result<UserGrants>

    suspend fun hasGrant(
        plugId: PlugId,
        permission: PlugPermission,
    ): Result<Boolean>
}

class SqlDelightUserGrantStore(
    private val database: Database,
    private val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    },
) : UserGrantStore {

    private val queries
        get() = database.plugGrantsQueries

    override suspend fun grant(
        plugId: PlugId,
        permission: PlugPermission,
        grantedAt: Instant,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                queries.upsertGrant(
                    plug_id = plugId.value,
                    permission_json = encode(permission),
                    granted_at = grantedAt.toEpochMilliseconds(),
                )
            }.map { }
        }

    override suspend fun revoke(
        plugId: PlugId,
        permission: PlugPermission,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                queries.revokeGrant(
                    plug_id = plugId.value,
                    permission_json = encode(permission),
                )
            }.map { }
        }

    override suspend fun listGrants(plugId: PlugId): Result<UserGrants> =
        withContext(ioDispatcher) {
            runCatching {
                val granted = queries.listGrants(plugId.value)
                    .executeAsList()
                    .map { row -> decode(row.permission_json) }

                UserGrants(granted = granted)
            }
        }

    override suspend fun hasGrant(
        plugId: PlugId,
        permission: PlugPermission,
    ): Result<Boolean> =
        withContext(ioDispatcher) {
            runCatching {
                queries.countGrant(
                    plug_id = plugId.value,
                    permission_json = encode(permission),
                ).executeAsOne() > 0
            }
        }

    private fun encode(permission: PlugPermission): String =
        json.encodeToString(PlugPermission.serializer(), permission)

    private fun decode(payload: String): PlugPermission =
        json.decodeFromString(PlugPermission.serializer(), payload)
}
