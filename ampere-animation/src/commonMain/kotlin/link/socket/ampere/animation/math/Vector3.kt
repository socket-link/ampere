package link.socket.ampere.animation.math

import kotlin.math.sqrt

/**
 * A 3D vector for position and direction calculations in world space.
 */
data class Vector3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(other: Vector3): Vector3 =
        Vector3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vector3): Vector3 =
        Vector3(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Float): Vector3 =
        Vector3(x * scalar, y * scalar, z * scalar)

    operator fun unaryMinus(): Vector3 =
        Vector3(-x, -y, -z)

    fun dot(other: Vector3): Float =
        x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3): Vector3 =
        Vector3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        )

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun lengthSquared(): Float = x * x + y * y + z * z

    fun normalized(): Vector3 {
        val len = length()
        return if (len > 0f) this * (1f / len) else ZERO
    }

    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
        val UP = Vector3(0f, 1f, 0f)
        val FORWARD = Vector3(0f, 0f, 1f)
        val RIGHT = Vector3(1f, 0f, 0f)
    }
}
