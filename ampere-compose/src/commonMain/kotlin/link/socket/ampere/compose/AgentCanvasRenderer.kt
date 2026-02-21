package link.socket.ampere.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentVisualState
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.projection.Camera
import link.socket.ampere.animation.projection.ScreenProjector
import kotlin.math.PI
import kotlin.math.sin

/**
 * Renders agent nodes onto a Compose Canvas.
 *
 * Each agent is drawn as a circle with state-dependent color and size,
 * cognitive phase glow ring, and processing shimmer effect.
 *
 * When a [Camera] is provided, agents are projected from 3D space with
 * depth-based scaling: nearby agents appear larger and more opaque,
 * while distant agents are smaller and more diffuse — creating a
 * parallax depth effect.
 */
object AgentCanvasRenderer {

    /**
     * Render all agents with optional 3D depth-based scaling.
     *
     * @param drawScope The Compose DrawScope to render into
     * @param agents Current agent layer
     * @param cellWidth Width of each cell in pixels
     * @param cellHeight Height of each cell in pixels
     * @param camera Optional camera for 3D depth projection. When null,
     *   falls back to flat 2D rendering using agent.position.
     */
    fun render(
        drawScope: DrawScope,
        agents: AgentLayer,
        cellWidth: Float,
        cellHeight: Float,
        camera: Camera? = null
    ) {
        if (camera == null) {
            render2D(drawScope, agents, cellWidth, cellHeight)
        } else {
            render3D(drawScope, agents, cellWidth, cellHeight, camera)
        }
    }

    /**
     * Flat 2D rendering — original behavior, no depth projection.
     */
    private fun render2D(
        drawScope: DrawScope,
        agents: AgentLayer,
        cellWidth: Float,
        cellHeight: Float
    ) {
        val baseRadius = cellWidth * 1.5f
        with(drawScope) {
            agents.allAgents.forEach { agent ->
                val cx = agent.position.x * cellWidth
                val cy = agent.position.y * cellHeight
                drawAgentNode(this, cx, cy, baseRadius, 0.8f, 0.9f, agent)
            }
        }
    }

    /**
     * 3D depth-projected rendering. Agents are sorted back-to-front and
     * scaled by their distance from the camera.
     */
    private fun render3D(
        drawScope: DrawScope,
        agents: AgentLayer,
        cellWidth: Float,
        cellHeight: Float,
        camera: Camera
    ) {
        val canvasWidth = drawScope.size.width
        val canvasHeight = drawScope.size.height
        val baseRadius = cellWidth * 1.5f

        // Pixel-based canvas uses charAspect 1.0 (unlike terminal's 0.5)
        val projector = ScreenProjector(
            screenWidth = canvasWidth.toInt().coerceAtLeast(1),
            screenHeight = canvasHeight.toInt().coerceAtLeast(1),
            charAspect = 1.0f
        )

        data class ProjectedAgent(
            val cx: Float,
            val cy: Float,
            val depth: Float,
            val agent: AgentVisualState
        )

        val projected = agents.allAgents.mapNotNull { agent ->
            val (screenX, screenY, depth) = projector.projectContinuous(agent.position3D, camera)
            if (depth in 0f..1f && screenX in -canvasWidth..canvasWidth * 2 && screenY in -canvasHeight..canvasHeight * 2) {
                ProjectedAgent(screenX, screenY, depth, agent)
            } else {
                null
            }
        }.sortedByDescending { it.depth } // Back-to-front for correct occlusion

        with(drawScope) {
            for ((cx, cy, depth, agent) in projected) {
                // Depth factor: near (depth≈0) → 1.0, far (depth≈1) → 0.0
                val depthFactor = 1f - depth.coerceIn(0f, 1f)

                val nodeRadius = baseRadius * (0.5f + 0.5f * depthFactor)
                val ringAlpha = if (agent.cognitivePhase != CognitivePhase.NONE) {
                    0.3f + 0.5f * depthFactor
                } else {
                    0.1f + 0.1f * depthFactor
                }
                val fillAlpha = 0.4f + 0.5f * depthFactor

                drawAgentNode(this, cx, cy, nodeRadius, ringAlpha, fillAlpha, agent)
            }
        }
    }

    /**
     * Draw a single agent node — shared between 2D and 3D paths.
     */
    private fun drawAgentNode(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        nodeRadius: Float,
        ringAlpha: Float,
        fillAlpha: Float,
        agent: AgentVisualState
    ) {
        with(drawScope) {
            // Outer ring: cognitive phase color
            val phaseColor = CognitivePalette.forPhase(agent.cognitivePhase)
            drawCircle(
                color = phaseColor,
                radius = nodeRadius + 3f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f),
                alpha = ringAlpha
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
                alpha = fillAlpha
            )

            // Processing shimmer (pulse phase)
            if (agent.state == AgentActivityState.PROCESSING) {
                val shimmerAlpha = (sin(agent.pulsePhase * 2 * PI.toFloat()) + 1f) / 4f
                drawCircle(
                    color = Color.White,
                    radius = nodeRadius * 0.8f,
                    center = Offset(cx, cy),
                    alpha = shimmerAlpha * fillAlpha
                )
            }
        }
    }
}
