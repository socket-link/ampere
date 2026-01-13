package link.socket.ampere.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import link.socket.ampere.db.Database

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

    return driver
}

/**
 * Run VACUUM on the database to reclaim disk space after deletions.
 * This is a blocking operation that can take several seconds for large databases.
 */
fun JdbcSqliteDriver.vacuum() {
    execute(null, "VACUUM", 0)
}

/**
 * Get the current database page count and size information.
 */
fun JdbcSqliteDriver.getDatabaseSize(): DatabaseSizeInfo {
    val pageCount = executeQuery(
        identifier = null,
        sql = "PRAGMA page_count",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
            )
        },
        parameters = 0,
        binders = null
    ).value

    val pageSize = executeQuery(
        identifier = null,
        sql = "PRAGMA page_size",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
                if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
            )
        },
        parameters = 0,
        binders = null
    ).value

    return DatabaseSizeInfo(
        pageCount = pageCount,
        pageSize = pageSize,
        totalBytes = pageCount * pageSize
    )
}

data class DatabaseSizeInfo(
    val pageCount: Long,
    val pageSize: Long,
    val totalBytes: Long,
) {
    val totalMB: Double get() = totalBytes / (1024.0 * 1024.0)
}
