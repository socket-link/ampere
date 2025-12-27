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
    driver.execute(null, "PRAGMA journal_mode=WAL", 0)  // Enable Write-Ahead Logging
    driver.execute(null, "PRAGMA synchronous=NORMAL", 0) // Balanced durability/performance
    driver.execute(null, "PRAGMA busy_timeout=5000", 0)  // Wait up to 5s on locks
    driver.execute(null, "PRAGMA cache_size=-64000", 0)  // 64MB cache

    // Ensure schema exists on first open. If it already exists, creation will simply fail and be ignored.
    runCatching {
        Database.Schema.create(driver)
    }

    return driver
}
