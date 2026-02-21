package link.socket.ampere.animation.projection

import link.socket.ampere.animation.math.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class CameraOrbitTest {

    private val epsilon = 1.5f  // Allow some wobble tolerance

    @Test
    fun `orbit returns to approximately starting position after full period`() {
        val orbit = CameraOrbit(
            radius = 15f,
            orbitSpeed = 0.1f,
            wobbleAmplitude = 0f  // Disable wobble for this test
        )
        val startCamera = orbit.currentCamera()
        val fullPeriod = (2f * PI.toFloat()) / 0.1f  // 2π / orbitSpeed

        // Advance through one full orbit in small steps
        val steps = 100
        val dt = fullPeriod / steps
        var camera = startCamera
        for (i in 0 until steps) {
            camera = orbit.update(dt)
        }

        assertTrue(
            abs(camera.position.x - startCamera.position.x) < epsilon,
            "x should return: start=${startCamera.position.x}, end=${camera.position.x}"
        )
        assertTrue(
            abs(camera.position.z - startCamera.position.z) < epsilon,
            "z should return: start=${startCamera.position.z}, end=${camera.position.z}"
        )
    }

    @Test
    fun `camera always looks at target`() {
        val orbit = CameraOrbit()
        repeat(10) {
            val camera = orbit.update(0.5f)
            assertTrue(
                camera.target == Vector3.ZERO,
                "camera should always look at origin"
            )
        }
    }

    @Test
    fun `camera position changes over time`() {
        val orbit = CameraOrbit()
        val first = orbit.currentCamera()
        val second = orbit.update(1f)
        assertTrue(
            first.position != second.position,
            "camera should move after update"
        )
    }

    @Test
    fun `wobble causes height variation`() {
        val orbit = CameraOrbit(
            wobbleAmplitude = 2f,
            wobbleFrequency = 1f
        )
        val heights = (0 until 20).map {
            orbit.update(0.1f).position.y
        }
        val minHeight = heights.min()
        val maxHeight = heights.max()
        assertTrue(
            maxHeight - minHeight > 0.1f,
            "wobble should cause height variation, range was ${maxHeight - minHeight}"
        )
    }

    @Test
    fun `zero wobble amplitude produces constant height`() {
        val orbit = CameraOrbit(
            height = 8f,
            wobbleAmplitude = 0f
        )
        val heights = (0 until 10).map {
            orbit.update(0.5f).position.y
        }
        val allSameHeight = heights.all { abs(it - 8f) < 0.01f }
        assertTrue(allSameHeight, "height should be constant with zero wobble")
    }

    @Test
    fun `initial camera is at expected radius`() {
        val orbit = CameraOrbit(radius = 10f, wobbleAmplitude = 0f)
        val camera = orbit.currentCamera()
        val horizontalDist = kotlin.math.sqrt(
            camera.position.x * camera.position.x +
            camera.position.z * camera.position.z
        )
        assertTrue(
            abs(horizontalDist - 10f) < 0.1f,
            "initial horizontal distance should be ~10, got $horizontalDist"
        )
    }
}
