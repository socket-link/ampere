package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.relay.EventRelayService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains

/**
 * Tests for WatchCommand.
 *
 * Note: These tests only verify command-line help and option parsing.
 * Full integration testing with live event streaming is done through manual testing
 * or would require complex mocking of the EventRelayService.
 */
class WatchCommandTest {

    /**
     * Create a test context for use in tests.
     */
    private fun createTestContext(tempDir: File): AmpereContext {
        val dbPath = File(tempDir, "test.db").absolutePath
        return AmpereContext(databasePath = dbPath)
    }

    /**
     * Mock EventRelayService that returns empty flows.
     */
    private class MockEventRelayService : EventRelayService {
        override fun subscribeToLiveEvents(filters: EventRelayFilters): Flow<Event> = emptyFlow()

        override suspend fun replayEvents(
            fromTime: kotlinx.datetime.Instant,
            toTime: kotlinx.datetime.Instant,
            filters: EventRelayFilters
        ): Result<Flow<Event>> = Result.success(emptyFlow())
    }

    @Test
    fun `watch command help text shows usage`() {
        val mockService = MockEventRelayService()
        val command = WatchCommand(mockService)
        val result = command.test("--help")

        assertContains(result.output, "Watch events streaming")
        assertContains(result.output, "Examples")
        assertContains(result.output, "ampere watch")
        assertContains(result.output, "--filter")
        assertContains(result.output, "--agent")
    }

    @Test
    fun `watch command help shows filter option description`() {
        val mockService = MockEventRelayService()
        val command = WatchCommand(mockService)
        val result = command.test("--help")

        assertContains(result.output, "Filter by event type")
        assertContains(result.output, "Repeatable")
    }

    @Test
    fun `watch command help shows agent option description`() {
        val mockService = MockEventRelayService()
        val command = WatchCommand(mockService)
        val result = command.test("--help")

        assertContains(result.output, "Filter by agent ID")
        assertContains(result.output, "agent-pm")
    }

    @Test
    fun `watch command help shows examples for filtering`() {
        val mockService = MockEventRelayService()
        val command = WatchCommand(mockService)
        val result = command.test("--help")

        assertContains(result.output, "TaskCreated")
        assertContains(result.output, "QuestionRaised")
    }

    @Test
    fun `watch command can be created with real context`(@TempDir tempDir: File) {
        val context = createTestContext(tempDir)
        try {
            val command = WatchCommand(context.eventRelayService)
            val result = command.test("--help")

            assertContains(result.output, "Watch events streaming")
        } finally {
            context.close()
        }
    }
}
