package link.socket.ampere.subject

import app.cash.sqldelight.db.SqlDriver
import kotlinx.datetime.Clock

/**
 * The two Subject rows every database carries before anything registers (AMPR-416, F30): the
 * Principal under [SubjectRegistry.PRINCIPAL_ID] and the `system` pseudo-Subject under
 * [SubjectRegistry.SYSTEM_ID].
 *
 * Written with `INSERT OR IGNORE` straight through the driver so it can run inside
 * `DatabaseSchemaManager.ensure`'s transaction, on every open, before a `Database` exists: a
 * row that is there stays as it was, and a database that never saw `9.sqm` (one `Schema.create`
 * built fresh) gets them here. `9.sqm` writes the same two rows for a database migrated by a
 * driver that never calls this — Android's and iOS's bring their own schema forward.
 *
 * These rows are the only Subjects whose provenance names no event; their `created_by` is
 * [SubjectRegistry.SEED_PROVENANCE] here and `migration:9` from the migration.
 */
object SubjectSeed {

    /** Seed both rows if absent. Safe on any database at or past schema v9. */
    fun apply(driver: SqlDriver, clock: Clock = Clock.System) {
        val now = clock.now().toEpochMilliseconds()
        seed(driver, SubjectRegistry.PRINCIPAL_ID, SubjectKind.HUMAN, "Principal", now)
        seed(driver, SubjectRegistry.SYSTEM_ID, SubjectKind.AGENT, "system", now)
    }

    private fun seed(driver: SqlDriver, id: String, kind: SubjectKind, displayName: String, now: Long) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR IGNORE INTO SubjectStore(id, kind, display_name, created_by, created_at) " +
                "VALUES (?, ?, ?, ?, ?)",
            parameters = 5,
        ) {
            bindString(0, id)
            bindString(1, kind.name)
            bindString(2, displayName)
            bindString(3, SubjectRegistry.SEED_PROVENANCE)
            bindLong(4, now)
        }
    }
}
