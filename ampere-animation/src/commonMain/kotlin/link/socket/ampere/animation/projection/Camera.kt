package link.socket.ampere.animation.projection

import link.socket.ampere.animation.math.Matrix4
import link.socket.ampere.animation.math.Vector3

/**
 * Observing consciousness — the viewpoint from which the brain watches itself.
 *
 * The camera orbits slowly by default, giving a sense of dimensionality
 * without disorienting the viewer. Think of it as the observer's attention
 * drifting naturally around the cognitive field.
 *
 * @param position Where the camera is in world space
 * @param target What the camera looks at (usually the center of the waveform)
 * @param fovY Field of view in radians (wider = more distortion at edges)
 */
data class Camera(
    val position: Vector3,
    val target: Vector3 = Vector3.ZERO,
    val up: Vector3 = Vector3.UP,
    val fovY: Float = 0.8f,
    val near: Float = 0.1f,
    val far: Float = 100f,
    val projectionType: ProjectionType = ProjectionType.PERSPECTIVE
) {
    enum class ProjectionType { PERSPECTIVE, ORTHOGRAPHIC }

    /**
     * Build the combined view-projection matrix.
     * @param aspectRatio width/height of the output grid (in character cells,
     *        accounting for typical 2:1 character aspect ratio)
     */
    fun viewProjectionMatrix(aspectRatio: Float): Matrix4 {
        val view = Matrix4.lookAt(position, target, up)
        val projection = when (projectionType) {
            ProjectionType.PERSPECTIVE -> Matrix4.perspective(fovY, aspectRatio, near, far)
            ProjectionType.ORTHOGRAPHIC -> {
                val distance = (position - target).length()
                val height = distance * 2f
                val width = height * aspectRatio
                Matrix4.orthographic(width, height, near, far)
            }
        }
        return projection * view
    }
}
