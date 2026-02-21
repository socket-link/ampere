package link.socket.ampere.animation.projection

import link.socket.ampere.animation.math.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenProjectorTest {

    private val projector = ScreenProjector(screenWidth = 80, screenHeight = 24)

    private val frontCamera = Camera(
        position = Vector3(0f, 0f, 10f),
        target = Vector3.ZERO
    )

    @Test
    fun `target projects to approximately screen center`() {
        val point = projector.project(Vector3.ZERO, frontCamera)
        assertTrue(
            abs(point.x - 40) <= 2,
            "target x should be near center (40), got ${point.x}"
        )
        assertTrue(
            abs(point.y - 12) <= 2,
            "target y should be near center (12), got ${point.y}"
        )
        assertTrue(point.visible, "target should be visible")
    }

    @Test
    fun `point behind camera is not visible`() {
        val behindCamera = Vector3(0f, 0f, 15f)  // Behind camera at z=10
        val point = projector.project(behindCamera, frontCamera)
        assertFalse(point.visible, "point behind camera should not be visible")
    }

    @Test
    fun `depth increases for farther points`() {
        val nearPoint = projector.project(Vector3(0f, 0f, 5f), frontCamera)
        val farPoint = projector.project(Vector3(0f, 0f, -5f), frontCamera)
        assertTrue(
            farPoint.depth > nearPoint.depth,
            "far point depth (${farPoint.depth}) should exceed near point depth (${nearPoint.depth})"
        )
    }

    @Test
    fun `left world point projects to left screen half`() {
        val leftPoint = projector.project(Vector3(-3f, 0f, 0f), frontCamera)
        assertTrue(leftPoint.x < 40, "left point should project to left half, got x=${leftPoint.x}")
    }

    @Test
    fun `right world point projects to right screen half`() {
        val rightPoint = projector.project(Vector3(3f, 0f, 0f), frontCamera)
        assertTrue(rightPoint.x > 40, "right point should project to right half, got x=${rightPoint.x}")
    }

    @Test
    fun `point far off screen is not visible`() {
        val wayOff = Vector3(100f, 0f, 0f)
        val point = projector.project(wayOff, frontCamera)
        assertFalse(point.visible, "point far off screen should not be visible")
    }

    @Test
    fun `continuous projection returns sub-cell precision`() {
        val (cx, cy, _) = projector.projectContinuous(Vector3.ZERO, frontCamera)
        // Should be near center but with fractional component possible
        assertTrue(abs(cx - 40f) < 2f, "continuous x near center, got $cx")
        assertTrue(abs(cy - 12f) < 2f, "continuous y near center, got $cy")
    }

    @Test
    fun `different screen sizes shift projection`() {
        val small = ScreenProjector(screenWidth = 40, screenHeight = 12)
        val large = ScreenProjector(screenWidth = 160, screenHeight = 48)
        val smallPoint = small.project(Vector3(2f, 0f, 0f), frontCamera)
        val largePoint = large.project(Vector3(2f, 0f, 0f), frontCamera)
        assertTrue(
            largePoint.x > smallPoint.x,
            "larger screen should have larger x coordinate"
        )
    }

    @Test
    fun `orthographic camera also projects target to center`() {
        val orthoCamera = Camera(
            position = Vector3(0f, 0f, 10f),
            target = Vector3.ZERO,
            projectionType = Camera.ProjectionType.ORTHOGRAPHIC
        )
        val point = projector.project(Vector3.ZERO, orthoCamera)
        assertTrue(
            abs(point.x - 40) <= 2,
            "ortho target x should be near center, got ${point.x}"
        )
        assertTrue(
            abs(point.y - 12) <= 2,
            "ortho target y should be near center, got ${point.y}"
        )
    }
}
