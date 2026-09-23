package link.socket.ampere.cli.goal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Test

/**
 * The CLI's answer to Ctrl+C mid-Arc (AMPR-359): cancel the run, then hold the JVM — bounded —
 * until the run has settled and written its completion manifest. The hook body is driven directly;
 * a real shutdown cannot be staged in a test.
 */
class ArcShutdownHookTest {

    @Test
    fun `a shutdown cancels the run and holds until it has settled`() {
        val cancelled = AtomicBoolean(false)
        val hook = ArcShutdownHook(cancelRun = { cancelled.set(true) }, grace = 1.minutes)

        val shutdownDone = CountDownLatch(1)
        thread(name = "shutdown") {
            hook.onShutdown()
            shutdownDone.countDown()
        }

        // Still holding: the run has not reported that it settled.
        assertFalse(shutdownDone.await(200, TimeUnit.MILLISECONDS), "Shutdown must wait for the run")
        assertTrue(cancelled.get(), "Shutdown must cancel the run it is waiting on")

        hook.release()

        assertTrue(shutdownDone.await(10, TimeUnit.SECONDS), "Releasing the hook must let shutdown finish")
    }

    @Test
    fun `a run that never settles holds shutdown only for the grace period`() {
        val hook = ArcShutdownHook(cancelRun = {}, grace = 50.milliseconds)

        val shutdownDone = CountDownLatch(1)
        thread(name = "shutdown") {
            hook.onShutdown()
            shutdownDone.countDown()
        }

        assertTrue(shutdownDone.await(10, TimeUnit.SECONDS), "Shutdown must give up after the grace period")
    }

    @Test
    fun `release is safe twice on a hook that was never installed`() {
        val hook = ArcShutdownHook(cancelRun = {})

        hook.release()
        hook.release()
    }
}
