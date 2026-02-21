package link.socket.ampere.animation.projection

import link.socket.ampere.animation.math.Vector3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slowly orbits the camera around the target point.
 * The drift is intentionally organic — slight wobble in the orbit path
 * makes it feel like a living observer rather than a mechanical turntable.
 *
 * @param radius Distance from target in the XZ plane
 * @param height Elevation above the target
 * @param orbitSpeed Radians per second of orbital rotation
 * @param wobbleAmplitude How much the height and radius vary
 * @param wobbleFrequency Frequency of the wobble oscillation
 */
class CameraOrbit(
    private val radius: Float = 15f,
    private val height: Float = 8f,
    private val orbitSpeed: Float = 0.1f,
    private val wobbleAmplitude: Float = 0.5f,
    private val wobbleFrequency: Float = 0.3f
) {
    private var angle: Float = 0f
    private var elapsed: Float = 0f

    /**
     * Advance the orbit by [dt] seconds and return the updated camera.
     */
    fun update(dt: Float): Camera {
        elapsed += dt
        angle += orbitSpeed * dt
        return currentCamera()
    }

    /**
     * Get the camera at the current orbit position without advancing time.
     */
    fun currentCamera(): Camera {
        val wobble = wobbleAmplitude * sin(elapsed * wobbleFrequency * TWO_PI)
        val r = radius + wobble
        val h = height + wobble * 0.5f

        val x = r * cos(angle)
        val z = r * sin(angle)

        return Camera(
            position = Vector3(x, h, z),
            target = Vector3.ZERO,
            up = Vector3.UP
        )
    }

    companion object {
        private const val TWO_PI = 2f * kotlin.math.PI.toFloat()
    }
}
