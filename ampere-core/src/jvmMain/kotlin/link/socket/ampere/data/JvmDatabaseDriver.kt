package link.socket.ampere.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import link.socket.ampere.db.Database
import link.socket.ampere.db.fts.FtsSchema

/** Creates a SQLDelight JDBC driver for the given database on JVM. */
fun createJvmDriver(
    dbName: String = "ampere.db",
): JdbcSqliteDriver {
    val url = if (dbName == JdbcSqliteDriver.IN_MEMORY) {
        dbName
    } else {
        "jdbc:sqlite:$dbName"
    }

    val driver = JdbcSqliteDriver(url)

    // Configure SQLite for better concurrency
    driver.execute(null, "PRAGMA journal_mode=WAL", 0) // Enable Write-Ahead Logging
    driver.execute(null, "PRAGMA synchronous=NORMAL", 0) // Balanced durability/performance
    driver.execute(null, "PRAGMA busy_timeout=5000", 0) // Wait up to 5s on locks
    driver.execute(null, "PRAGMA cache_size=-64000", 0) // 64MB cache

    // Ensure schema exists on first open. If it already exists, creation will simply fail and be ignored.
    runCatching {
        Database.Schema.create(driver)
    }

    // FTS5 virtual tables are no longer part of Schema.create() (see FtsSchema) so a SQLite
    // build without the fts5 module degrades search instead of taking the whole schema down.
    // xerial's sqlite-jdbc, used here, compiles fts5 in, so this is expected to always succeed
    // on JVM — but installing it the same guarded way as every other platform keeps the
    // behavior consistent and catches a genuine regression instead of masking it.
    FtsSchema.install(driver)

    return driver
}
