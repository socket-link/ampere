package link.socket.ampere.animation.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class Matrix4Test {

    private val epsilon = 0.01f

    private fun assertApprox(expected: Float, actual: Float, msg: String = "") {
        assertTrue(
            abs(expected - actual) < epsilon,
            "$msg expected $expected but got $actual"
        )
    }

    private fun assertApprox(expected: Vector3, actual: Vector3, msg: String = "") {
        assertApprox(expected.x, actual.x, "$msg x:")
        assertApprox(expected.y, actual.y, "$msg y:")
        assertApprox(expected.z, actual.z, "$msg z:")
    }

    @Test
    fun `identity transform preserves vector`() {
        val v = Vector3(1f, 2f, 3f)
        assertApprox(v, Matrix4.identity().transform(v))
    }

    @Test
    fun `identity times identity is identity`() {
        val id = Matrix4.identity()
        val result = id * id
        assertTrue(id.data.contentEquals(result.data))
    }

    @Test
    fun `rotateY 90 degrees maps FORWARD to approximately RIGHT`() {
        val rot = Matrix4.rotateY((PI / 2).toFloat())
        val result = rot.transform(Vector3.FORWARD)
        assertApprox(Vector3.RIGHT, result, "rotateY(PI/2) * FORWARD:")
    }

    @Test
    fun `rotateY 180 degrees maps FORWARD to negative FORWARD`() {
        val rot = Matrix4.rotateY(PI.toFloat())
        val result = rot.transform(Vector3.FORWARD)
        assertApprox(-Vector3.FORWARD, result, "rotateY(PI) * FORWARD:")
    }

    @Test
    fun `rotateX 90 degrees maps UP to FORWARD`() {
        val rot = Matrix4.rotateX((PI / 2).toFloat())
        val result = rot.transform(Vector3.UP)
        assertApprox(Vector3.FORWARD, result, "rotateX(PI/2) * UP:")
    }

    @Test
    fun `translate moves point`() {
        val t = Matrix4.translate(Vector3(1f, 2f, 3f))
        val result = t.transform(Vector3.ZERO)
        assertApprox(Vector3(1f, 2f, 3f), result)
    }

    @Test
    fun `translate does not affect directions`() {
        val t = Matrix4.translate(Vector3(10f, 20f, 30f))
        val result = t.transformDirection(Vector3.UP)
        assertApprox(Vector3.UP, result)
    }

    @Test
    fun `rotation preserves direction length`() {
        val rot = Matrix4.rotateY(0.7f) * Matrix4.rotateX(0.3f)
        val dir = Vector3(1f, 1f, 1f).normalized()
        val result = rot.transformDirection(dir)
        assertApprox(1f, result.length(), "rotated direction length:")
    }

    @Test
    fun `combined rotation and translation`() {
        val t = Matrix4.translate(Vector3(5f, 0f, 0f))
        val r = Matrix4.rotateY(0f)
        val combined = t * r
        val result = combined.transform(Vector3.ZERO)
        assertApprox(Vector3(5f, 0f, 0f), result)
    }

    @Test
    fun `lookAt camera at origin looking along Z`() {
        val view = Matrix4.lookAt(
            eye = Vector3(0f, 0f, 5f),
            target = Vector3.ZERO,
            up = Vector3.UP
        )
        // The target (origin) should map to z = -5 in view space (looking along -Z)
        val result = view.transform(Vector3.ZERO)
        // Point at origin, camera at z=5 looking toward origin: result.z should be negative
        assertTrue(result.z < 0f, "target should be in front of camera (negative z), got ${result.z}")
    }

    @Test
    fun `perspective projection compresses depth`() {
        val proj = Matrix4.perspective(
            fovY = (PI / 4).toFloat(),
            aspect = 1f,
            near = 0.1f,
            far = 100f
        )
        val nearPoint = proj.transform(Vector3(0f, 0f, -0.1f))
        val farPoint = proj.transform(Vector3(0f, 0f, -100f))
        // Near point should have smaller z than far point in NDC
        assertTrue(nearPoint.z < farPoint.z, "near z (${nearPoint.z}) should be less than far z (${farPoint.z})")
    }

    @Test
    fun `orthographic projection preserves relative positions`() {
        val proj = Matrix4.orthographic(
            width = 10f,
            height = 10f,
            near = 0.1f,
            far = 100f
        )
        val left = proj.transform(Vector3(-2f, 0f, -5f))
        val right = proj.transform(Vector3(2f, 0f, -5f))
        assertTrue(left.x < right.x, "left (${left.x}) should be less than right (${right.x})")
    }

    @Test
    fun `matrix multiplication is associative`() {
        val a = Matrix4.rotateY(0.5f)
        val b = Matrix4.translate(Vector3(1f, 0f, 0f))
        val c = Matrix4.rotateX(0.3f)

        val ab_c = (a * b) * c
        val a_bc = a * (b * c)

        val testPoint = Vector3(1f, 2f, 3f)
        assertApprox(ab_c.transform(testPoint), a_bc.transform(testPoint), "associativity:")
    }
}
