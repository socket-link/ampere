package link.socket.ampere.compose

import androidx.compose.ui.graphics.drawscope.DrawScope
import link.socket.ampere.animation.particle.ParticleSystem

/**
 * Renders particles onto a Compose Canvas.
 *
 * Maps each particle to a positioned, colored circle with size and
 * alpha derived from particle type and remaining life.
 */
object ParticleCanvasRenderer {

    /**
     * Render all alive particles.
     *
     * @param drawScope The Compose DrawScope to render into
     * @param particles Current particle system
     * @param cellWidth Width of each cell in pixels
     * @param cellHeight Height of each cell in pixels
     */
    fun render(
        drawScope: DrawScope,
        particles: ParticleSystem,
        cellWidth: Float,
        cellHeight: Float
    ) {
        // TODO: Task 5 — Full implementation
    }
}
