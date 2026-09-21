package link.socket.ampere

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for AmpereContext.
 *
 * These tests verify that the context can be initialized without errors
 * and provides access to the required services.
 */
class AmpereContextTest {

    @Test
    fun `context can be instantiated`(@TempDir tempDir: File) {
        val dbPath = File(tempDir, "test.db").absolutePath

        val context = AmpereContext(databasePath = dbPath)
        try {
            assertNotNull(context.environmentService, "EnvironmentService should be initialized")
            assertNotNull(context.eventRelayService, "EventRelayService should be initialized")
        } finally {
            context.close()
        }
    }

    @Test
    fun `context creates database file`(@TempDir tempDir: File) {
        val dbPath = File(tempDir, "test.db").absolutePath

        val context = AmpereContext(databasePath = dbPath)
        try {
            assertTrue(File(dbPath).exists(), "Database file should be created")
        } finally {
            context.close()
        }
    }

    @Test
    fun `context creates parent directories if needed`(@TempDir tempDir: File) {
        val nestedPath = File(tempDir, "nested/dir/test.db").absolutePath

        val context = AmpereContext(databasePath = nestedPath)
        try {
            assertTrue(File(nestedPath).exists(), "Database file should be created in nested directory")
            assertTrue(
                File(nestedPath).parentFile.isDirectory,
                "Parent directories should be created"
            )
        } finally {
            context.close()
        }
    }

    @Test
    fun `context can be started`(@TempDir tempDir: File) {
        val dbPath = File(tempDir, "test.db").absolutePath

        val context = AmpereContext(databasePath = dbPath)
        try {
            // Should not throw
            context.start()
        } finally {
            context.close()
        }
    }

    @Test
    fun `context provides access to environment service repositories`(@TempDir tempDir: File) {
        val dbPath = File(tempDir, "test.db").absolutePath

        val context = AmpereContext(databasePath = dbPath)
        try {
            val env = context.environmentService

            assertNotNull(env.eventRepository, "Event repository should be available")
            assertNotNull(env.messageRepository, "Message repository should be available")
            assertNotNull(env.meetingRepository, "Meeting repository should be available")
            assertNotNull(env.ticketRepository, "Ticket repository should be available")
        } finally {
            context.close()
        }
    }

    @Test
    fun `context provides access to event bus`(@TempDir tempDir: File) {
        val dbPath = File(tempDir, "test.db").absolutePath

        val context = AmpereContext(databasePath = dbPath)
        try {
            assertNotNull(context.environmentService.eventBus, "EventBus should be available")
        } finally {
            context.close()
        }
    }

    @Test
    fun `multiple contexts can coexist with different databases`(@TempDir tempDir: File) {
        val dbPath1 = File(tempDir, "test1.db").absolutePath
        val dbPath2 = File(tempDir, "test2.db").absolutePath

        val context1 = AmpereContext(databasePath = dbPath1)
        val context2 = AmpereContext(databasePath = dbPath2)

        try {
            assertNotNull(context1.environmentService)
            assertNotNull(context2.environmentService)
            assertTrue(File(dbPath1).exists())
            assertTrue(File(dbPath2).exists())
        } finally {
            context1.close()
            context2.close()
        }
    }

    @Test
    fun `context can create agent APIs`(@TempDir tempDir: File) {
        val dbPath = File(tempDir, "test.db").absolutePath

        val context = AmpereContext(databasePath = dbPath)
        try {
            val env = context.environmentService

            val messageApi = env.createMessageApi("test-agent")
            assertNotNull(messageApi, "Should be able to create message API")

            val eventApi = env.createEventApi("test-agent")
            assertNotNull(eventApi, "Should be able to create event API")

            val meetingsApi = env.createMeetingsApi("test-agent")
            assertNotNull(meetingsApi, "Should be able to create meetings API")
        } finally {
            context.close()
        }
    }

    @Test
    fun `context close is idempotent`(@TempDir tempDir: File) {
        val dbPath = File(tempDir, "test.db").absolutePath

        val context = AmpereContext(databasePath = dbPath)

        // Should not throw when called multiple times
        context.close()
        context.close()
        context.close()
    }

    /**
     * AMPR-356: points `user.home` at a temp dir so the no-arg constructor never opens
     * (and, since AMPR-333, migrates) the developer's real `~/.ampere/ampere.db`.
     * `defaultDatabasePath()` reads the property at call time, so no production hook is
     * needed. The swap is process-global; it is safe because JUnit parallel execution is
     * not enabled (Gradle's `maxParallelForks` runs test classes in separate JVMs).
     */
    @Test
    fun `default database path uses home directory`(@TempDir tempDir: File) {
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            val context = AmpereContext()
            try {
                val expectedPath = File(tempDir, ".ampere/ampere.db")

                // The database should exist after context creation
                assertTrue(
                    expectedPath.exists(),
                    "Database should be created at default path: ${expectedPath.absolutePath}"
                )
            } finally {
                context.close()
            }
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome)
            } else {
                System.clearProperty("user.home")
            }
        }
    }
}
