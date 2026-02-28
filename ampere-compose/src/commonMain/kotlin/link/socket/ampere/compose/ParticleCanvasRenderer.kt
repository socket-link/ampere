package link.socket.ampere.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import link.socket.phosphor.field.Particle
import link.socket.phosphor.field.ParticleSystem
import link.socket.phosphor.field.ParticleType

/**
 * Renders particles onto a Compose Canvas.
 *
 * Maps each particle to a positioned, colored circle with size and
 * alpha derived from particle type and remaining life. A larger
 * translucent outer circle provides a glow effect.
 */
object ParticleCanvasRenderer {

    /** Compute particle radius from life and cell width. */
    internal fun particleRadius(life: Float, cellWidth: Float): Float =
        (life * 4f + 1f) * (cellWidth / 4)

    /** Outer glow alpha scales linearly with life. */
    internal fun glowAlpha(life: Float): Float = life * 0.2f

    /** Core circle alpha scales linearly with life. */
    internal fun coreAlpha(life: Float): Float = life * 0.8f

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
        with(drawScope) {
            particles.getParticles().forEach { particle ->
                val cx = particle.position.x * cellWidth + cellWidth / 2
                val cy = particle.position.y * cellHeight + cellHeight / 2
                val radius = particleRadius(particle.life, cellWidth)
                val color = colorForParticle(particle)

                // Glow effect: outer translucent circle
                drawCircle(
                    color = color,
                    radius = radius * 2f,
                    center = Offset(cx, cy),
                    alpha = glowAlpha(particle.life)
                )

                // Core: solid circle
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(cx, cy),
                    alpha = coreAlpha(particle.life)
                )
            }
        }
    }

    internal fun colorForParticle(particle: Particle): Color {
        return when (particle.type) {
            ParticleType.MOTE -> CognitivePalette.substrateMid
            ParticleType.SPARK -> CognitivePalette.sparkAccent
            ParticleType.TRAIL -> CognitivePalette.flowToken
            ParticleType.RIPPLE -> CognitivePalette.substrateBright
        }
    }
}
