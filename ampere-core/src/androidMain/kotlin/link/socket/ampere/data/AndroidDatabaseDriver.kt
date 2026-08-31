package link.socket.ampere.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.osmerion.android.database.sqlite.OsmerionSQLiteOpenHelperFactory
import link.socket.ampere.db.Database

/**
 * The [SupportSQLiteOpenHelper.Factory] that the ampere-core schema requires on Android.
 *
 * The schema declares FTS5 virtual tables (`knowledge_chunks_fts`, `KnowledgeFts`,
 * `OutcomeMemoryFts`). Android's system SQLite ships FTS3/FTS4 but not FTS5, so
 * `CREATE VIRTUAL TABLE ... USING fts5` fails with `no such module: fts5`. Because
 * `SQLiteOpenHelper.onCreate` runs inside a transaction, that single failure rolls back
 * the *entire* schema — the database is never created, no version row is written, and
 * every later open retries and fails identically. Links, knowledge, memory and the
 * persisted event bus all become unreadable, not just search.
 *
 * This factory is backed by a bundled SQLite build that has FTS5 compiled in, which keeps
 * FTS5 semantics identical to iOS (Apple's SQLite enables FTS5) and desktop/JVM (xerial's
 * `sqlite-jdbc` compiles it in). It costs roughly 1.2–1.8 MB of native code per ABI.
 *
 * Consumers that build their own [AndroidSqliteDriver] instead of calling
 * [createAndroidDriver] **must** pass this as the driver's `factory` argument. Using
 * SQLDelight's default `FrameworkSQLiteOpenHelperFactory` reintroduces the failure above.
 */
fun ampereSqliteOpenHelperFactory(): SupportSQLiteOpenHelper.Factory = OsmerionSQLiteOpenHelperFactory()

/**
 * Creates a SQLDelight Android driver for the given database on Android.
 *
 * The returned driver is already open: the schema is created eagerly so that a failure
 * surfaces here, with a diagnosable message, rather than as an opaque `SQLiteException`
 * on whichever unrelated query happens to run first.
 *
 * @throws AmpereDatabaseInitializationException if the database cannot be opened or created.
 */
fun createAndroidDriver(
    context: Context,
    dbName: String = "ampere.db",
): AndroidSqliteDriver {
    val driver = AndroidSqliteDriver(
        schema = Database.Schema,
        context = context,
        name = dbName,
        factory = ampereSqliteOpenHelperFactory(),
    )

    // AndroidSqliteDriver opens lazily, so force the open (and therefore Schema.create)
    // now rather than letting it blow up inside an arbitrary caller's query.
    try {
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1",
            mapper = { QueryResult.Unit },
            parameters = 0,
        ).value
    } catch (cause: Throwable) {
        // Throwable, not Exception: a bundled-SQLite build whose native library is missing
        // for the device's ABI fails with UnsatisfiedLinkError, which is an Error.
        throw AmpereDatabaseInitializationException(dbName, cause)
    }

    return driver
}

/**
 * Thrown when the ampere-core database cannot be opened or created on Android.
 *
 * Consumers should treat this as fatal: with no database, every [link.socket.ampere.link.LinkStore],
 * knowledge and memory read will fail. Catching it and logging a skip hides a total loss of
 * persistence behind an unrelated symptom.
 */
class AmpereDatabaseInitializationException(
    dbName: String,
    cause: Throwable,
) : IllegalStateException(buildMessage(dbName, cause), cause) {

    private companion object {
        fun buildMessage(
            dbName: String,
            cause: Throwable,
        ): String = buildString {
            append("Could not open or create the ampere-core database '")
            append(dbName)
            append("'. ")

            if (cause.message?.contains("no such module: fts5", ignoreCase = true) == true) {
                append(
                    "This SQLite build has no FTS5 module, so creating the schema's virtual " +
                        "tables rolls back the whole schema. Construct the driver with " +
                        "link.socket.ampere.data.createAndroidDriver(), or pass " +
                        "link.socket.ampere.data.ampereSqliteOpenHelperFactory() as the " +
                        "`factory` argument of AndroidSqliteDriver — the framework SQLite " +
                        "default does not support FTS5.",
                )
            } else {
                append("Ampere has no persistence without it; this is not recoverable by retrying.")
            }
        }
    }
}
