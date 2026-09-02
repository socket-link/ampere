package link.socket.ampere.data

import com.osmerion.android.database.sqlite.OsmerionSQLiteOpenHelperFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for AMPR-324.
 *
 * The ampere-core schema declares FTS5 virtual tables, and Android's system SQLite has no
 * FTS5 module. Because `SQLiteOpenHelper.onCreate` runs in a transaction, that one failing
 * `CREATE VIRTUAL TABLE` rolls back the whole schema, leaving the database permanently
 * uncreatable — every Link, knowledge and memory read throws, not just search.
 *
 * These assertions are deliberately about the *type* of the factory rather than about
 * opening a database: the bundled SQLite is an Android `.so` that a desktop JVM cannot
 * load, so the open path itself can only be covered by an instrumented test on a device.
 */
class AndroidDatabaseDriverTest {

    @Test
    fun `driver factory is the bundled SQLite rather than the framework default`() {
        val factory = ampereSqliteOpenHelperFactory()

        // SQLDelight's default is FrameworkSQLiteOpenHelperFactory, i.e. the system SQLite,
        // which is exactly the configuration that cannot create this schema.
        assertTrue(
            factory is OsmerionSQLiteOpenHelperFactory,
            "ampere-core needs a SQLite build with FTS5; got ${factory::class.qualifiedName}",
        )
    }
}
