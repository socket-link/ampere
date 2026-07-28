package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency

class SurfacePolicyPriorityOrderingTest {

    private fun emission(
        surfaces: List<Surface> = emptyList(),
        fallbackUrl: String? = null,
    ): Emission {
        val payload = EmissionPayload.Decision(prompt = "Proceed?")
        return Emission(
            id = "emission-1",
            kind = EmissionKind.Decision,
            payload = payload,
            provenance = EmissionProvenance(inputDigest = inputDigest(payload)),
            producedAt = Instant.fromEpochMilliseconds(0),
            surfaces = surfaces,
            fallbackUrl = fallbackUrl,
        )
    }

    @Test
    fun `first reachable surface in declared priority wins`() {
        val policy = DefaultSurfacePolicy(
            availability = SurfaceAvailability { it == Surface.Push || it == Surface.Console },
        )

        val resolution = policy.resolve(
            emission(surfaces = listOf(Surface.Foreground, Surface.Push, Surface.Console)),
        )

        assertEquals(Surface.Push, resolution.surface)
    }

    @Test
    fun `unreachable surfaces are skipped in order`() {
        val policy = DefaultSurfacePolicy(availability = SurfaceAvailability.ConsoleOnly)

        val resolution = policy.resolve(
            emission(surfaces = listOf(Surface.Foreground, Surface.Push, Surface.Console)),
        )

        assertEquals(Surface.Console, resolution.surface)
    }

    @Test
    fun `empty declared priority falls back to urgency-derived default`() {
        val policy = DefaultSurfacePolicy(
            availability = SurfaceAvailability { it == Surface.Foreground },
        )

        val resolution = policy.resolve(emission(surfaces = emptyList()), urgency = Urgency.MEDIUM)

        assertEquals(Surface.Foreground, resolution.surface)
    }

    @Test
    fun `low urgency default priority is console only`() {
        val policy = DefaultSurfacePolicy(
            availability = SurfaceAvailability { it == Surface.Push || it == Surface.Foreground },
        )

        val resolution = policy.resolve(emission(surfaces = emptyList()), urgency = Urgency.LOW)

        assertEquals(Surface.Console, resolution.surface)
    }

    @Test
    fun `fallbackUrl is carried through only when Push is chosen`() {
        val pushPolicy = DefaultSurfacePolicy(availability = SurfaceAvailability { it == Surface.Push })
        val pushResolution = pushPolicy.resolve(
            emission(surfaces = listOf(Surface.Push, Surface.Console), fallbackUrl = "https://example.com/respond"),
        )
        assertEquals(Surface.Push, pushResolution.surface)
        assertEquals("https://example.com/respond", pushResolution.fallbackUrl)

        val consolePolicy = DefaultSurfacePolicy(availability = SurfaceAvailability.ConsoleOnly)
        val consoleResolution = consolePolicy.resolve(
            emission(surfaces = listOf(Surface.Push, Surface.Console), fallbackUrl = "https://example.com/respond"),
        )
        assertEquals(Surface.Console, consoleResolution.surface)
        assertNull(consoleResolution.fallbackUrl)
    }
}
