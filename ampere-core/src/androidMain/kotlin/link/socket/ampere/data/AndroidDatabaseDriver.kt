package link.socket.ampere.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.osmerion.android.database.sqlite.OsmerionSQLiteOpenHelperFactory
import link.socket.ampere.db.Database
import link.socket.ampere.db.fts.FtsSchema

/**
 * The [SupportSQLiteOpenHelper.Factory] that gives the ampere-core database ranked FTS5
 * search on Android.
 *
 * Android's system SQLite ships FTS3/FTS4 but not FTS5. The FTS5 virtual tables
 * (`knowledge_chunks_fts`, `KnowledgeFts`, `OutcomeMemoryFts`) are no longer part of
 * `Database.Schema.create()` (see [FtsSchema]), so a missing module here no longer takes the
 * whole database down — [createAndroidDriver] degrades to the `*Like` fallback queries instead.
 * This factory exists so that degradation isn't necessary in the first place: it's backed by a
 * bundled SQLite build with FTS5 compiled in, keeping ranked search parity with iOS (Apple's
 * SQLite enables FTS5) and desktop/JVM (xerial's `sqlite-jdbc` compiles it in). It costs roughly
 * 1.2–1.8 MB of native code per ABI.
 *
 * Consumers that build their own [AndroidSqliteDriver] instead of calling
 * [createAndroidDriver] should pass this as the driver's `factory` argument to get ranked
 * search; using SQLDelight's default `FrameworkSQLiteOpenHelperFactory` still works, but falls
 * back to `LIKE`-based search.
 */
fun ampereSqliteOpenHelperFactory(): SupportSQLiteOpenHelper.Factory = OsmerionSQLiteOpenHelperFactory()

/**
 * Creates a SQLDelight Android driver for the given database on Android.
 *
 * The returned driver is already open: the schema is created eagerly, and the FTS5 virtual
 * tables are installed as a guarded step immediately after (see [FtsSchema]), so a failure
 * surfaces here, with a diagnosable message, rather than as an opaque `SQLiteException`
 * on whichever unrelated query happens to run first.
 *
 * The driver receives [Database.Schema] and creates or migrates it itself off `PRAGMA
 * user_version`, so Android doesn't go through the JVM's `DatabaseSchemaManager`.
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

    // Runs after the SELECT 1 above forces onCreate's transaction to have already committed
    // (SupportSQLiteOpenHelper.Callback.onOpen would be the equivalent hook), so a missing
    // fts5 module here only records FtsAvailability.Unavailable (and logs a warning) — it can
    // no longer roll back the schema created above. Repositories re-derive this same result
    // lazily on first FTS query, so it's safe to also let it happen here purely for early,
    // observable logging.
    FtsSchema.install(driver)

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
) : IllegalStateException(buildMessage(dbName), cause) {

    private companion object {
        fun buildMessage(dbName: String): String = buildString {
            append("Could not open or create the ampere-core database '")
            append(dbName)
            append("'. ")
            append("Ampere has no persistence without it; this is not recoverable by retrying.")
        }
    }
}
