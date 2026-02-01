package link.socket.ampere.cli.animation.substrate

import kotlin.math.sqrt

/**
 * A simple 2D vector for position and direction calculations.
 */
data class Vector2(
    val x: Float,
    val y: Float
) {
    operator fun plus(other: Vector2): Vector2 = Vector2(x + other.x, y + other.y)
    operator fun minus(other: Vector2): Vector2 = Vector2(x - other.x, y - other.y)
    operator fun times(scalar: Float): Vector2 = Vector2(x * scalar, y * scalar)
    operator fun div(scalar: Float): Vector2 = Vector2(x / scalar, y / scalar)

    fun length(): Float = sqrt(x * x + y * y)

    fun normalized(): Vector2 {
        val len = length()
        return if (len > 0f) this / len else ZERO
    }

    fun dot(other: Vector2): Float = x * other.x + y * other.y

    fun lerp(other: Vector2, t: Float): Vector2 {
        return Vector2(
            x + (other.x - x) * t,
            y + (other.y - y) * t
        )
    }

    companion object {
        val ZERO = Vector2(0f, 0f)
        val UP = Vector2(0f, -1f)
        val DOWN = Vector2(0f, 1f)
        val LEFT = Vector2(-1f, 0f)
        val RIGHT = Vector2(1f, 0f)
    }
}

/**
 * A point in 2D integer space.
 */
data class Point(
    val x: Int,
    val y: Int
) {
    fun toVector2(): Vector2 = Vector2(x.toFloat(), y.toFloat())
}
