package link.socket.ampere.cli.ondevice

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.routing.local.LocalUnavailableReason
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.inferenceProbe
import link.socket.ampere.cli.ondevice.FakeAmpereHelper.Companion.probe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The public way to the helper engine (AMPR-469), which `ampere-desktop` binds its
 * `OnDeviceInferenceSession` to: an engine when the locator finds a helper, the locator's reason
 * when it does not, and a helper that ends when the result is closed.
 */
class OnDeviceHelperEngineTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `a found helper is an engine that spawns nothing until its first probe`() = runBlocking<Unit> {
        val fake = FakeAmpereHelper(directory)
        val script = fake.script(listOf(probe(), inferenceProbe("available", contextSize = 4096)))

        OnDeviceHelperEngine.locate(namedLocator(script.path)).use { found ->
            val engine = assertIs<OnDeviceHelperEngine.Present>(found).engine
            assertEquals(0, fake.spawns(), "locating starts no process")

            val capacity = engine.probe()

            assertTrue(capacity.available)
            assertEquals(4096, capacity.maxContextTokens)
            assertEquals(1, fake.spawns())
        }
    }

    @Test
    fun `closing the result ends the helper, and nothing spawns after`() = runBlocking<Unit> {
        val fake = FakeAmpereHelper(directory)
        val script = fake.script(listOf(probe(), inferenceProbe("available", contextSize = 4096)))
        val found = assertIs<OnDeviceHelperEngine.Present>(OnDeviceHelperEngine.locate(namedLocator(script.path)))
        assertTrue(found.engine.probe().available)

        found.close()
        val afterClose = found.engine.probe()

        assertFalse(afterClose.available)
        assertEquals(LocalUnavailableReason.HELPER_UNREACHABLE, afterClose.reason)
        assertEquals(1, fake.spawns())
    }

    @Test
    fun `a host without a helper is absent, with the locator's reason`() {
        val found = OnDeviceHelperEngine.locate(AmpereHelperLocator(osName = "Windows 11"))

        val reason = assertIs<OnDeviceHelperEngine.Absent>(found).reason
        assertTrue(reason.contains("macOS"), reason)
        assertTrue(reason.contains("Windows 11"), reason)
    }

    private fun namedLocator(path: String) = AmpereHelperLocator(
        osName = "Mac OS X",
        property = { if (it == AmpereHelperLocator.HELPER_PROPERTY) path else null },
        environment = { null },
        installDirectory = null,
    )
}
