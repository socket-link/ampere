package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.math.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SurfaceLightingTest {

    private val lighting = SurfaceLighting()

    @Test
    fun `surface facing light returns high luminance`() {
        // Normal pointing toward light direction
        val normal = lighting.lightDir
        val viewDir = Vector3(0f, 0.5f, 1f).normalized()

        val luminance = lighting.computeLuminance(normal, viewDir)
        assertTrue(
            luminance > 0.7f,
            "surface facing light should have high luminance, got $luminance"
        )
    }

    @Test
    fun `surface facing away returns only ambient`() {
        // Normal pointing opposite to light direction
        val normal = (-lighting.lightDir)
        val viewDir = Vector3(0f, 0.5f, 1f).normalized()

        val luminance = lighting.computeLuminance(normal, viewDir)
        assertTrue(
            luminance <= lighting.ambientLight + 0.01f,
            "surface facing away should return ~ambient (${lighting.ambientLight}), got $luminance"
        )
    }

    @Test
    fun `luminance is always in 0 to 1 range`() {
        val normals = listOf(
            Vector3.UP,
            Vector3.FORWARD,
            Vector3.RIGHT,
            Vector3(-1f, -1f, -1f).normalized(),
            Vector3(0.5f, 0.8f, -0.2f).normalized()
        )
        val viewDir = Vector3(0f, 0.3f, 1f).normalized()

        for (normal in normals) {
            val luminance = lighting.computeLuminance(normal, viewDir)
            assertTrue(luminance in 0f..1f, "luminance must be in [0,1], got $luminance for normal=$normal")
        }
    }

    @Test
    fun `specular highlight appears at correct angle`() {
        // Set up a case where the half-vector between light and view aligns with normal
        // Light direction is default: (-0.5, 1.0, -0.3) normalized
        val viewDir = Vector3(0f, 0f, 1f).normalized()
        val halfVec = (lighting.lightDir + viewDir).normalized()

        // Normal aligned with half-vector should give max specular
        val luminanceAligned = lighting.computeLuminance(halfVec, viewDir)

        // Normal perpendicular to half-vector should give no specular
        val perpNormal = halfVec.cross(Vector3.RIGHT).normalized()
        val luminancePerp = lighting.computeLuminance(perpNormal, viewDir)

        assertTrue(
            luminanceAligned > luminancePerp,
            "specular-aligned ($luminanceAligned) should be brighter than perpendicular ($luminancePerp)"
        )
    }

    @Test
    fun `zero ambient lighting still produces diffuse`() {
        val darkLighting = SurfaceLighting(ambientLight = 0f)
        val normal = darkLighting.lightDir
        val viewDir = Vector3(0f, 0.5f, 1f).normalized()

        val luminance = darkLighting.computeLuminance(normal, viewDir)
        assertTrue(luminance > 0f, "diffuse should produce positive luminance even with zero ambient")
    }

    @Test
    fun `custom light direction is normalized`() {
        val custom = SurfaceLighting(lightDirection = Vector3(10f, 10f, 10f))
        val len = custom.lightDir.length()
        assertTrue(
            abs(len - 1f) < 0.001f,
            "light direction should be normalized, got length $len"
        )
    }

    @Test
    fun `upward normal gives significant luminance with default light`() {
        // Default light has strong upward component (y=1.0)
        val luminance = lighting.computeLuminance(Vector3.UP, Vector3(0f, 1f, 1f).normalized())
        assertTrue(
            luminance > 0.5f,
            "upward normal with default light should be reasonably bright, got $luminance"
        )
    }
}
