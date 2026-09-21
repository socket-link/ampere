package link.socket.ampere.data

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import link.socket.ampere.db.Database

/**
 * Brings a JVM database to [Database.Schema]'s version, and is the only JVM caller of
 * `Database.Schema.create` and `Database.Schema.migrate`.
 *
 * Android's `AndroidSqliteDriver` and iOS's `NativeSqliteDriver` are handed `Database.Schema`
 * and create or migrate off `PRAGMA user_version` themselves. `JdbcSqliteDriver` does neither,
 * so every JVM open site calls [ensure] before using the driver (and before `FtsSchema.install`).
 *
 * JVM databases opened before this existed were built by a bare `Schema.create` that never
 * stamped `user_version`, so they read as v0 with tables present. [inferLegacyVersion] recovers
 * their version from the schema itself so the ordinary migration path can take them forward.
 */
object DatabaseSchemaManager {

    sealed interface SchemaState {
        /** The database was empty and the full schema was created. */
        data object Created : SchemaState

        /** The database was at [from] and has been migrated to [to]. */
        data class Migrated(val from: Long, val to: Long) : SchemaState

        /** The database was already at [Database.Schema]'s version. */
        data object Current : SchemaState
    }

    private val logger = Logger.withTag("DatabaseSchemaManager")

    /**
     * Creates the schema on an empty database, or migrates an existing one to
     * [Database.Schema]'s version, stamping `PRAGMA user_version` either way.
     *
     * Runs in a single transaction, so a migration that fails partway leaves the database as it
     * was instead of half-migrated under a version stamp that would make the retry fail too.
     *
     * @return the resulting [SchemaState], or a failure if the schema could not be brought
     * current — an [IllegalStateException] if the database is newer than this build's schema.
     */
    fun ensure(driver: SqlDriver): Result<SchemaState> = runCatching {
        val transacter = object : TransacterImpl(driver) {}
        transacter.transactionWithResult { ensureInTransaction(driver) }
    }

    private fun ensureInTransaction(driver: SqlDriver): SchemaState {
        val schemaVersion = Database.Schema.version
        var version = readUserVersion(driver)

        if (version == 0L) {
            if (!tableExists(driver, "EventStore")) {
                Database.Schema.create(driver)
                writeUserVersion(driver, schemaVersion)
                return SchemaState.Created
            }
            version = inferLegacyVersion(driver)
            writeUserVersion(driver, version)
            logger.i { "Unversioned legacy database inferred to be at schema v$version" }
        }

        return when {
            version < schemaVersion -> {
                Database.Schema.migrate(driver, version, schemaVersion)
                writeUserVersion(driver, schemaVersion)
                logger.i { "Migrated database from schema v$version to v$schemaVersion" }
                SchemaState.Migrated(from = version, to = schemaVersion)
            }
            version == schemaVersion -> SchemaState.Current
            else -> throw IllegalStateException(
                "database is at v$version, newer than schema v$schemaVersion",
            )
        }
    }

    internal fun readUserVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
            },
            parameters = 0,
        ).value ?: 0L

    internal fun writeUserVersion(driver: SqlDriver, version: Long) {
        // PRAGMA takes no bind parameters; version is a Long, so interpolating it is safe.
        driver.execute(identifier = null, sql = "PRAGMA user_version = $version", parameters = 0)
    }

    /**
     * Recovers the version of a database built by the old bare `Schema.create` path, which left
     * `user_version` at 0. Only meaningful when `user_version` is 0 and `EventStore` exists.
     *
     * - 1: `EventStore` has no `run_id` (built before 1.sqm).
     * - 2: `run_id` but no `Links` table (built before 2.sqm).
     * - 3: both.
     *
     * Never above 3: from v3 on every JVM open stamps `user_version`, so no unversioned database
     * can be newer than that.
     */
    internal fun inferLegacyVersion(driver: SqlDriver): Long = when {
        "run_id" !in columnNames(driver, "EventStore") -> 1
        !tableExists(driver, "Links") -> 2
        else -> 3
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            mapper = { cursor -> QueryResult.Value(cursor.next().value) },
            parameters = 1,
        ) {
            bindString(0, table)
        }.value

    private fun columnNames(driver: SqlDriver, table: String): Set<String> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM pragma_table_info(?)",
            mapper = { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.next().value) {
                    cursor.getString(0)?.let(names::add)
                }
                QueryResult.Value(names)
            },
            parameters = 1,
        ) {
            bindString(0, table)
        }.value
}
