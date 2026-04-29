package link.socket.ampere.plugin.permission

import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.db.Database
import link.socket.ampere.util.ioDispatcher

interface UserGrantStore {

    suspend fun grant(
        pluginId: String,
        permission: PluginPermission,
        grantedAt: Instant = Clock.System.now(),
    ): Result<Unit>

    suspend fun revoke(
        pluginId: String,
        permission: PluginPermission,
    ): Result<Unit>

    suspend fun listGrants(pluginId: String): Result<UserGrants>

    suspend fun hasGrant(
        pluginId: String,
        permission: PluginPermission,
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
        get() = database.pluginGrantsQueries

    override suspend fun grant(
        pluginId: String,
        permission: PluginPermission,
        grantedAt: Instant,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                queries.upsertGrant(
                    plugin_id = pluginId,
                    permission_json = encode(permission),
                    granted_at = grantedAt.toEpochMilliseconds(),
                )
            }.map { }
        }

    override suspend fun revoke(
        pluginId: String,
        permission: PluginPermission,
    ): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                queries.revokeGrant(
                    plugin_id = pluginId,
                    permission_json = encode(permission),
                )
            }.map { }
        }

    override suspend fun listGrants(pluginId: String): Result<UserGrants> =
        withContext(ioDispatcher) {
            runCatching {
                val granted = queries.listGrants(pluginId)
                    .executeAsList()
                    .map { row -> decode(row.permission_json) }

                UserGrants(granted = granted)
            }
        }

    override suspend fun hasGrant(
        pluginId: String,
        permission: PluginPermission,
    ): Result<Boolean> =
        withContext(ioDispatcher) {
            runCatching {
                queries.countGrant(
                    plugin_id = pluginId,
                    permission_json = encode(permission),
                ).executeAsOne() > 0
            }
        }

    private fun encode(permission: PluginPermission): String =
        json.encodeToString(PluginPermission.serializer(), permission)

    private fun decode(payload: String): PluginPermission =
        json.decodeFromString(PluginPermission.serializer(), payload)
}
