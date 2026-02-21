package link.socket.ampere.animation.math

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Vector3Test {

    private val epsilon = 0.001f

    private fun assertApprox(expected: Float, actual: Float, msg: String = "") {
        assertTrue(
            kotlin.math.abs(expected - actual) < epsilon,
            "$msg expected $expected but got $actual"
        )
    }

    private fun assertApprox(expected: Vector3, actual: Vector3, msg: String = "") {
        assertApprox(expected.x, actual.x, "$msg x:")
        assertApprox(expected.y, actual.y, "$msg y:")
        assertApprox(expected.z, actual.z, "$msg z:")
    }

    @Test
    fun `addition works`() {
        val a = Vector3(1f, 2f, 3f)
        val b = Vector3(4f, 5f, 6f)
        assertEquals(Vector3(5f, 7f, 9f), a + b)
    }

    @Test
    fun `subtraction works`() {
        val a = Vector3(5f, 7f, 9f)
        val b = Vector3(1f, 2f, 3f)
        assertEquals(Vector3(4f, 5f, 6f), a - b)
    }

    @Test
    fun `scalar multiplication works`() {
        assertEquals(Vector3(2f, 4f, 6f), Vector3(1f, 2f, 3f) * 2f)
    }

    @Test
    fun `unary minus negates all components`() {
        assertEquals(Vector3(-1f, -2f, -3f), -Vector3(1f, 2f, 3f))
    }

    @Test
    fun `dot product is correct`() {
        val a = Vector3(1f, 0f, 0f)
        val b = Vector3(0f, 1f, 0f)
        assertEquals(0f, a.dot(b))
        assertEquals(1f, a.dot(a))
    }

    @Test
    fun `cross product of X and Y gives Z`() {
        val result = Vector3.RIGHT.cross(Vector3.UP)
        assertApprox(Vector3.FORWARD, result)
    }

    @Test
    fun `cross product of Y and X gives negative Z`() {
        val result = Vector3.UP.cross(Vector3.RIGHT)
        assertApprox(-Vector3.FORWARD, result)
    }

    @Test
    fun `length of unit vector is 1`() {
        assertApprox(1f, Vector3.UP.length())
        assertApprox(1f, Vector3.RIGHT.length())
        assertApprox(1f, Vector3.FORWARD.length())
    }

    @Test
    fun `length of 3-4-0 vector`() {
        assertApprox(5f, Vector3(3f, 4f, 0f).length())
    }

    @Test
    fun `normalized vector has length 1`() {
        val v = Vector3(3f, 4f, 5f).normalized()
        assertApprox(1f, v.length())
    }

    @Test
    fun `normalized zero vector returns zero`() {
        assertEquals(Vector3.ZERO, Vector3.ZERO.normalized())
    }

    @Test
    fun `lengthSquared avoids sqrt`() {
        assertEquals(14f, Vector3(1f, 2f, 3f).lengthSquared())
    }

    @Test
    fun `ZERO has all zero components`() {
        assertEquals(0f, Vector3.ZERO.x)
        assertEquals(0f, Vector3.ZERO.y)
        assertEquals(0f, Vector3.ZERO.z)
    }
}
