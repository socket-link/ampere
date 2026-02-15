package link.socket.ampere.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.flow.FlowState

/**
 * Renders flow connections between agents onto a Compose Canvas.
 *
 * Draws connection paths as lines with animated token indicators
 * showing active handoffs between agents.
 */
object FlowCanvasRenderer {

    /**
     * Render flow connections and active handoffs.
     *
     * @param drawScope The Compose DrawScope to render into
     * @param flow Current flow layer
     * @param cellWidth Width of each cell in pixels
     * @param cellHeight Height of each cell in pixels
     */
    fun render(
        drawScope: DrawScope,
        flow: FlowLayer,
        cellWidth: Float,
        cellHeight: Float
    ) {
        with(drawScope) {
            flow.allConnections.forEach { connection ->
                if (connection.path.size < 2) return@forEach

                // Draw the path
                val path = Path().apply {
                    val first = connection.path.first()
                    moveTo(first.x * cellWidth, first.y * cellHeight)
                    connection.path.drop(1).forEach { point ->
                        lineTo(point.x * cellWidth, point.y * cellHeight)
                    }
                }

                val pathColor = when (connection.state) {
                    FlowState.DORMANT -> CognitivePalette.flowDormant
                    FlowState.ACTIVATING -> CognitivePalette.flowActive
                    FlowState.TRANSMITTING -> CognitivePalette.flowActive
                    FlowState.RECEIVED -> CognitivePalette.agentComplete
                }

                val pathAlpha = when (connection.state) {
                    FlowState.DORMANT -> 0.15f
                    FlowState.ACTIVATING -> 0.4f
                    FlowState.TRANSMITTING -> 0.7f
                    FlowState.RECEIVED -> 0.5f
                }

                drawPath(
                    path = path,
                    color = pathColor,
                    alpha = pathAlpha,
                    style = Stroke(width = 2f)
                )

                // Draw task token
                connection.taskToken?.let { token ->
                    val tx = token.position.x * cellWidth
                    val ty = token.position.y * cellHeight
                    drawCircle(
                        color = CognitivePalette.flowToken,
                        radius = cellWidth * 0.6f,
                        center = Offset(tx, ty),
                        alpha = 0.9f
                    )
                }
            }

            // Draw trail particles
            flow.allTrailParticles.forEach { particle ->
                val cx = particle.position.x * cellWidth
                val cy = particle.position.y * cellHeight
                drawCircle(
                    color = CognitivePalette.flowToken,
                    radius = cellWidth * 0.3f * particle.life,
                    center = Offset(cx, cy),
                    alpha = particle.life * 0.5f
                )
            }
        }
    }
}
