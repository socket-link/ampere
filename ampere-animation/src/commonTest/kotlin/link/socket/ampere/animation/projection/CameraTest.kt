package link.socket.ampere.animation.projection

import link.socket.ampere.animation.math.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CameraTest {

    private val epsilon = 0.01f

    @Test
    fun `viewProjectionMatrix produces a non-null result`() {
        val camera = Camera(position = Vector3(0f, 5f, 10f))
        val vp = camera.viewProjectionMatrix(1.0f)
        assertNotNull(vp)
    }

    @Test
    fun `target projects to approximately screen center in perspective`() {
        val camera = Camera(
            position = Vector3(0f, 0f, 10f),
            target = Vector3.ZERO,
            projectionType = Camera.ProjectionType.PERSPECTIVE
        )
        val vp = camera.viewProjectionMatrix(1.0f)
        val projected = vp.transform(Vector3.ZERO)
        // In NDC, center is (0, 0)
        assertTrue(abs(projected.x) < 0.1f, "target x should be near 0, got ${projected.x}")
        assertTrue(abs(projected.y) < 0.1f, "target y should be near 0, got ${projected.y}")
    }

    @Test
    fun `target projects to approximately screen center in orthographic`() {
        val camera = Camera(
            position = Vector3(0f, 0f, 10f),
            target = Vector3.ZERO,
            projectionType = Camera.ProjectionType.ORTHOGRAPHIC
        )
        val vp = camera.viewProjectionMatrix(1.0f)
        val projected = vp.transform(Vector3.ZERO)
        assertTrue(abs(projected.x) < 0.1f, "target x should be near 0, got ${projected.x}")
        assertTrue(abs(projected.y) < 0.1f, "target y should be near 0, got ${projected.y}")
    }

    @Test
    fun `points left of target project to negative x`() {
        val camera = Camera(
            position = Vector3(0f, 0f, 10f),
            target = Vector3.ZERO
        )
        val vp = camera.viewProjectionMatrix(1.0f)
        val leftPoint = vp.transform(Vector3(-3f, 0f, 0f))
        assertTrue(leftPoint.x < 0f, "point to left should have negative x, got ${leftPoint.x}")
    }

    @Test
    fun `points above target project to positive y`() {
        val camera = Camera(
            position = Vector3(0f, 0f, 10f),
            target = Vector3.ZERO
        )
        val vp = camera.viewProjectionMatrix(1.0f)
        val abovePoint = vp.transform(Vector3(0f, 3f, 0f))
        assertTrue(abovePoint.y > 0f, "point above should have positive y, got ${abovePoint.y}")
    }

    @Test
    fun `camera with different projection types produce different matrices`() {
        val persp = Camera(
            position = Vector3(0f, 0f, 10f),
            projectionType = Camera.ProjectionType.PERSPECTIVE
        )
        val ortho = Camera(
            position = Vector3(0f, 0f, 10f),
            projectionType = Camera.ProjectionType.ORTHOGRAPHIC
        )
        val perspPoint = persp.viewProjectionMatrix(1f).transform(Vector3(2f, 0f, 0f))
        val orthoPoint = ortho.viewProjectionMatrix(1f).transform(Vector3(2f, 0f, 0f))
        // Perspective and orthographic should produce different x values
        assertTrue(
            abs(perspPoint.x - orthoPoint.x) > 0.01f,
            "perspective and orthographic should differ"
        )
    }
}
