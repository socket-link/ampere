package link.socket.ampere.agents.domain.emission

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

class SurfacePolicyConsoleFallbackTest {

    private val originalOut = System.out
    private val originalIn = System.`in`
    private val capturedOut = ByteArrayOutputStream()

    @BeforeTest
    fun setup() {
        System.setOut(PrintStream(capturedOut))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setIn(originalIn)
    }

    private fun decisionEmission(): Emission {
        val payload = EmissionPayload.Decision(prompt = "Deploy to production?")
        return Emission(
            id = "emission-console",
            kind = EmissionKind.Decision,
            payload = payload,
            affordances = listOf(
                Affordance(id = "yes", label = "Yes", signalPayload = kotlinx.serialization.json.JsonPrimitive("yes")),
                Affordance(id = "no", label = "No", signalPayload = kotlinx.serialization.json.JsonPrimitive("no")),
            ),
            provenance = EmissionProvenance(inputDigest = inputDigest(payload)),
            producedAt = Instant.fromEpochMilliseconds(0),
        )
    }

    @Test
    fun `policy falls back to Console when nothing declared is reachable`() {
        val policy = DefaultSurfacePolicy(availability = SurfaceAvailability { false })

        val resolution = policy.resolve(
            Emission(
                id = "emission-unreachable",
                kind = EmissionKind.Decision,
                payload = EmissionPayload.Decision(prompt = "?"),
                provenance = EmissionProvenance(inputDigest = "digest"),
                producedAt = Instant.fromEpochMilliseconds(0),
                surfaces = listOf(Surface.Push, Surface.Foreground),
            ),
        )

        assertEquals(Surface.Console, resolution.surface)
    }

    @Test
    fun `printPrompt writes the prompt and affordances to stdout`() {
        ConsoleSurfaceIO.printPrompt(decisionEmission())

        val printed = capturedOut.toString()
        assertTrue(printed.contains("Deploy to production?"))
        assertTrue(printed.contains("Yes"))
        assertTrue(printed.contains("No"))
    }

    @Test
    fun `promptAndAwaitReply prints the prompt and reads the reply from stdin`() {
        System.setIn(ByteArrayInputStream("yes\n".toByteArray()))

        val reply = ConsoleSurfaceIO.promptAndAwaitReply(decisionEmission())

        assertEquals("yes", reply)
        assertTrue(capturedOut.toString().contains("Deploy to production?"))
    }
}
