package link.socket.ampere.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.CognitivePhase
import kotlin.math.PI
import kotlin.math.sin

/**
 * Renders agent nodes onto a Compose Canvas.
 *
 * Each agent is drawn as a circle with state-dependent color and size,
 * cognitive phase glow ring, and processing shimmer effect.
 *
 * Note: Agent name/status text labels require Compose Text overlays
 * (Canvas doesn't have cross-platform drawText). The visual demo works
 * without labels since nodes are self-documenting through color and motion.
 */
object AgentCanvasRenderer {

    /**
     * Render all agents.
     *
     * @param drawScope The Compose DrawScope to render into
     * @param agents Current agent layer
     * @param cellWidth Width of each cell in pixels
     * @param cellHeight Height of each cell in pixels
     */
    fun render(
        drawScope: DrawScope,
        agents: AgentLayer,
        cellWidth: Float,
        cellHeight: Float
    ) {
        with(drawScope) {
            agents.allAgents.forEach { agent ->
                val cx = agent.position.x * cellWidth
                val cy = agent.position.y * cellHeight
                val nodeRadius = cellWidth * 1.5f

                // Outer ring: cognitive phase color
                val phaseColor = CognitivePalette.forPhase(agent.cognitivePhase)
                drawCircle(
                    color = phaseColor,
                    radius = nodeRadius + 3f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f),
                    alpha = if (agent.cognitivePhase != CognitivePhase.NONE) 0.8f else 0.2f
                )

                // Inner fill: agent state color
                val stateColor = when (agent.state) {
                    AgentActivityState.IDLE -> CognitivePalette.agentIdle
                    AgentActivityState.ACTIVE -> CognitivePalette.agentActive
                    AgentActivityState.PROCESSING -> CognitivePalette.agentProcessing
                    AgentActivityState.COMPLETE -> CognitivePalette.agentComplete
                    AgentActivityState.SPAWNING -> CognitivePalette.agentIdle
                }

                drawCircle(
                    color = stateColor,
                    radius = nodeRadius,
                    center = Offset(cx, cy),
                    alpha = 0.9f
                )

                // Processing shimmer (pulse phase)
                if (agent.state == AgentActivityState.PROCESSING) {
                    val shimmerAlpha = (sin(agent.pulsePhase * 2 * PI.toFloat()) + 1f) / 4f
                    drawCircle(
                        color = Color.White,
                        radius = nodeRadius * 0.8f,
                        center = Offset(cx, cy),
                        alpha = shimmerAlpha
                    )
                }
            }
        }
    }
}
