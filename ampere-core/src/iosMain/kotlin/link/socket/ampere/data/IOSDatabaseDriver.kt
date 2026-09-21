package link.socket.ampere.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import link.socket.ampere.db.Database
import link.socket.ampere.db.fts.FtsSchema

/**
 * Creates a Native SQLDelight driver for the given database on iOS.
 *
 * The driver receives [Database.Schema] and creates or migrates it itself off `PRAGMA
 * user_version`, so iOS doesn't go through the JVM's `DatabaseSchemaManager`.
 */
fun createIosDriver(
    dbName: String = "ampere.db",
) = NativeSqliteDriver(Database.Schema, dbName).also { driver ->
    // FTS5 virtual tables are no longer part of Schema.create() (see FtsSchema) so a SQLite
    // build without the fts5 module degrades search instead of taking the whole schema down.
    // Apple's bundled SQLite compiles fts5 in, so this is expected to always succeed on iOS —
    // but installing it the same guarded way as every other platform keeps the behavior
    // consistent and catches a genuine regression instead of masking it.
    FtsSchema.install(driver)
}
