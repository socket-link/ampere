package link.socket.ampere.animation.projection

import link.socket.ampere.animation.math.Vector3

/**
 * Projects 3D world coordinates to 2D screen (character grid) coordinates.
 *
 * Critical detail: terminal characters are typically ~2x taller than they are wide.
 * A "square" in world space must be corrected for this aspect ratio or circles
 * become ellipses and the 3D illusion breaks.
 *
 * @param screenWidth Width of the character grid
 * @param screenHeight Height of the character grid
 * @param charAspect The width:height ratio of a single character cell (typically ~0.5)
 */
class ScreenProjector(
    val screenWidth: Int,
    val screenHeight: Int,
    val charAspect: Float = 0.5f
) {
    data class ScreenPoint(
        val x: Int,
        val y: Int,
        val depth: Float,
        val visible: Boolean
    )

    /**
     * Effective aspect ratio of the grid, accounting for character shape.
     * A 80x24 grid of 2:1 characters has effective pixel ratio of 80*0.5 / 24 = 1.67
     */
    private val effectiveAspect: Float = (screenWidth.toFloat() * charAspect) / screenHeight.toFloat()

    /**
     * Project a 3D point to screen coordinates.
     */
    fun project(worldPoint: Vector3, camera: Camera): ScreenPoint {
        val (cx, cy, depth) = projectContinuous(worldPoint, camera)
        val ix = cx.toInt()
        val iy = cy.toInt()
        val visible = ix in 0 until screenWidth &&
                iy in 0 until screenHeight &&
                depth in 0f..1f
        return ScreenPoint(ix, iy, depth, visible)
    }

    /**
     * Project and return continuous (non-integer) coordinates for sub-cell precision.
     * Returns (screenX, screenY, depth) where depth is 0 (near) to 1 (far).
     */
    fun projectContinuous(worldPoint: Vector3, camera: Camera): Triple<Float, Float, Float> {
        val vp = camera.viewProjectionMatrix(effectiveAspect)
        val ndc = vp.transform(worldPoint)

        // NDC is in [-1, 1] range. Map to screen coordinates.
        // X: -1 = left edge, +1 = right edge
        // Y: +1 = top edge, -1 = bottom edge (flip for screen-space where y grows downward)
        val screenX = (ndc.x + 1f) * 0.5f * screenWidth
        val screenY = (1f - ndc.y) * 0.5f * screenHeight

        // Depth: NDC z from [-1, 1] mapped to [0, 1]
        val depth = (ndc.z + 1f) * 0.5f

        return Triple(screenX, screenY, depth)
    }
}
