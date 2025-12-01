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

    // Ensure schema exists on first open. If it already exists, creation will simply fail and be ignored.
    runCatching {
        Database.Schema.create(driver)
    }

    return driver
}
