package link.socket.ampere.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import link.socket.ampere.db.Database

/** Creates a Native SQLDelight driver for the given database on iOS. */
fun createIosDriver(
    dbName: String = "ampere.db",
) = NativeSqliteDriver(Database.Schema, dbName)
