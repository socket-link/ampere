package link.socket.ampere.animation.flow

import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Utility for calculating paths between agents.
 *
 * Paths use bezier curves to create smooth, visually appealing
 * connections that flow through the substrate.
 */
object FlowPath {

    /**
     * Calculate a path between two points using a quadratic bezier curve.
     *
     * @param from Starting position
     * @param to Ending position
     * @param steps Number of points in the path
     * @param curvature How much the path curves (positive = up, negative = down)
     * @return List of points along the path
     */
    fun calculatePath(
        from: Vector2,
        to: Vector2,
        steps: Int = 20,
        curvature: Float = 3f
    ): List<Vector2> {
        // Calculate a control point for the bezier curve
        // Default curvature goes "up" (negative y in screen coordinates)
        val midX = (from.x + to.x) / 2
        val midY = (from.y + to.y) / 2

        // Determine curve direction based on relative positions
        val curveOffset = if (from.y <= to.y) -curvature else curvature
        val control = Vector2(midX, midY + curveOffset)

        return quadraticBezier(from, control, to, steps)
    }

    /**
     * Calculate a path with a custom control point.
     */
    fun calculatePathWithControl(
        from: Vector2,
        control: Vector2,
        to: Vector2,
        steps: Int = 20
    ): List<Vector2> {
        return quadraticBezier(from, control, to, steps)
    }

    /**
     * Calculate a cubic bezier path with two control points.
     */
    fun calculateCubicPath(
        from: Vector2,
        control1: Vector2,
        control2: Vector2,
        to: Vector2,
        steps: Int = 20
    ): List<Vector2> {
        return cubicBezier(from, control1, control2, to, steps)
    }

    /**
     * Calculate a straight line path.
     */
    fun calculateLinearPath(
        from: Vector2,
        to: Vector2,
        steps: Int = 20
    ): List<Vector2> {
        return (0..steps).map { i ->
            val t = i.toFloat() / steps
            Vector2(
                x = from.x + (to.x - from.x) * t,
                y = from.y + (to.y - from.y) * t
            )
        }
    }

    /**
     * Quadratic bezier curve (one control point).
     *
     * B(t) = (1-t)²P₀ + 2(1-t)tP₁ + t²P₂
     */
    private fun quadraticBezier(
        p0: Vector2,
        p1: Vector2,
        p2: Vector2,
        steps: Int
    ): List<Vector2> {
        return (0..steps).map { i ->
            val t = i.toFloat() / steps
            val oneMinusT = 1f - t

            Vector2(
                x = oneMinusT.pow(2) * p0.x + 2 * oneMinusT * t * p1.x + t.pow(2) * p2.x,
                y = oneMinusT.pow(2) * p0.y + 2 * oneMinusT * t * p1.y + t.pow(2) * p2.y
            )
        }
    }

    /**
     * Cubic bezier curve (two control points).
     *
     * B(t) = (1-t)³P₀ + 3(1-t)²tP₁ + 3(1-t)t²P₂ + t³P₃
     */
    private fun cubicBezier(
        p0: Vector2,
        p1: Vector2,
        p2: Vector2,
        p3: Vector2,
        steps: Int
    ): List<Vector2> {
        return (0..steps).map { i ->
            val t = i.toFloat() / steps
            val oneMinusT = 1f - t

            Vector2(
                x = oneMinusT.pow(3) * p0.x +
                    3 * oneMinusT.pow(2) * t * p1.x +
                    3 * oneMinusT * t.pow(2) * p2.x +
                    t.pow(3) * p3.x,
                y = oneMinusT.pow(3) * p0.y +
                    3 * oneMinusT.pow(2) * t * p1.y +
                    3 * oneMinusT * t.pow(2) * p2.y +
                    t.pow(3) * p3.y
            )
        }
    }

    /**
     * Calculate the total length of a path.
     */
    fun pathLength(path: List<Vector2>): Float {
        if (path.size < 2) return 0f

        return path.zipWithNext { a, b ->
            val dx = b.x - a.x
            val dy = b.y - a.y
            sqrt(dx * dx + dy * dy)
        }.sum()
    }

    /**
     * Get a position along the path at a given progress (0.0 to 1.0).
     * Uses linear interpolation between path points.
     */
    fun positionAtProgress(path: List<Vector2>, progress: Float): Vector2 {
        if (path.isEmpty()) return Vector2.ZERO
        if (path.size == 1) return path[0]

        val clampedProgress = progress.coerceIn(0f, 1f)
        val index = (clampedProgress * (path.size - 1)).toInt()
        val nextIndex = (index + 1).coerceAtMost(path.size - 1)

        if (index == nextIndex) return path[index]

        // Calculate local progress between these two points
        val segmentLength = 1f / (path.size - 1)
        val localProgress = (clampedProgress - (index * segmentLength)) / segmentLength

        return Vector2(
            x = path[index].x + (path[nextIndex].x - path[index].x) * localProgress,
            y = path[index].y + (path[nextIndex].y - path[index].y) * localProgress
        )
    }

    /**
     * Get the direction vector at a given progress along the path.
     */
    fun directionAtProgress(path: List<Vector2>, progress: Float): Vector2 {
        if (path.size < 2) return Vector2(1f, 0f)

        val clampedProgress = progress.coerceIn(0f, 1f)
        val index = (clampedProgress * (path.size - 1)).toInt().coerceIn(0, path.size - 2)

        val from = path[index]
        val to = path[index + 1]

        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)

        return if (length > 0.001f) {
            Vector2(dx / length, dy / length)
        } else {
            Vector2(1f, 0f)
        }
    }
}

/**
 * Easing functions for smooth animations.
 */
object FlowEasing {

    /**
     * Linear easing (no acceleration).
     */
    fun linear(t: Float): Float = t

    /**
     * Ease in (slow start).
     */
    fun easeIn(t: Float): Float = t * t

    /**
     * Ease out (slow end).
     */
    fun easeOut(t: Float): Float = t * (2 - t)

    /**
     * Ease in-out (slow start and end).
     */
    fun easeInOut(t: Float): Float {
        return if (t < 0.5f) {
            2 * t * t
        } else {
            -1 + (4 - 2 * t) * t
        }
    }

    /**
     * Cubic ease in.
     */
    fun easeInCubic(t: Float): Float = t * t * t

    /**
     * Cubic ease out.
     */
    fun easeOutCubic(t: Float): Float {
        val t1 = t - 1
        return t1 * t1 * t1 + 1
    }

    /**
     * Cubic ease in-out.
     */
    fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4 * t * t * t
        } else {
            val t1 = 2 * t - 2
            0.5f * t1 * t1 * t1 + 1
        }
    }

    /**
     * Elastic ease out (overshoot and bounce back).
     */
    fun easeOutElastic(t: Float): Float {
        if (t == 0f) return 0f
        if (t == 1f) return 1f

        val p = 0.3f
        val s = p / 4

        return 2f.pow(-10 * t) * kotlin.math.sin((t - s) * (2 * kotlin.math.PI.toFloat()) / p) + 1
    }

    /**
     * Back ease out (slight overshoot).
     */
    fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1
        val t1 = t - 1

        return 1 + c3 * t1 * t1 * t1 + c1 * t1 * t1
    }
}
