package link.socket.ampere.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import link.socket.ampere.db.Database

/** Creates a SQLDelight Android driver for the given database on Android. */
fun createAndroidDriver(
    context: Context,
    dbName: String = "ampere.db",
) = AndroidSqliteDriver(Database.Schema, context, dbName)
