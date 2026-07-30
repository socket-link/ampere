package link.socket.ampere.plug.spi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

class ExecuteSinkTest {

    private val linkId = LinkId("google-oauth-1")
    private val executedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

    data class SendReminder(val title: String)

    /** A canon-bearing sink: consumes REMINDER, hands back the created object's handle. */
    private inner class ReminderSink : ExecuteSink<SendReminder> {
        override val consumes: Set<CanonType> = setOf(CanonType.REMINDER)
        override suspend fun execute(command: SendReminder): Result<ExecuteReceipt> =
            Result.success(
                ExecuteReceipt(
                    linkId = linkId,
                    executedAt = executedAt,
                    handle = SourceHandle(linkId = linkId, sourceSystem = "apple.reminders", nativeId = "r-1"),
                ),
            )
    }

    data class SendNotification(val title: String, val body: String)

    /** A canon-external sink: consumes nothing, C has no CanonEntity-bearing payload. */
    private inner class NotifySink : ExecuteSink<SendNotification> {
        override val consumes: Set<CanonType> = emptySet()
        override suspend fun execute(command: SendNotification): Result<ExecuteReceipt> =
            Result.success(ExecuteReceipt(linkId = linkId, executedAt = executedAt))
    }

    @Test
    fun `a canon-bearing sink and a canon-external sink compile against the same interface`() = runTest {
        val reminders: ExecuteSink<SendReminder> = ReminderSink()
        val notify: ExecuteSink<SendNotification> = NotifySink()

        val reminderReceipt = reminders.execute(SendReminder("Standup")).getOrThrow()
        val notifyReceipt = notify.execute(SendNotification("Standup", "in 5 minutes")).getOrThrow()

        assertEquals(setOf(CanonType.REMINDER), reminders.consumes)
        assertEquals("r-1", reminderReceipt.handle?.nativeId)
        assertEquals(emptySet(), notify.consumes)
        assertNull(notifyReceipt.handle)
    }
}
