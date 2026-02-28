package link.socket.ampere.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.render.Camera
import link.socket.phosphor.render.ScreenProjector
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase
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

    /** Map depth (0=near, 1=far) to a factor (1=near, 0=far). */
    internal fun depthFactor(depth: Float): Float = 1f - depth.coerceIn(0f, 1f)

    /** Scale node radius by depth: near agents are full-size, far agents are half. */
    internal fun nodeRadius(baseRadius: Float, depthFactor: Float): Float =
        baseRadius * (0.5f + 0.5f * depthFactor)

    /** Outer ring alpha: active phases are brighter, NONE phase is dim. */
    internal fun ringAlpha(depthFactor: Float, phase: CognitivePhase): Float =
        if (phase != CognitivePhase.NONE) {
            0.3f + 0.5f * depthFactor
        } else {
            0.1f + 0.1f * depthFactor
        }

    /** Inner fill alpha scaled by depth. */
    internal fun fillAlpha(depthFactor: Float): Float = 0.4f + 0.5f * depthFactor

    /** Processing shimmer alpha oscillating in [0, 0.5]. */
    internal fun shimmerAlpha(pulsePhase: Float): Float =
        (sin(pulsePhase * 2 * PI.toFloat()) + 1f) / 4f

    /** Map agent activity state to its display color. */
    internal fun colorForState(state: AgentActivityState): Color = when (state) {
        AgentActivityState.IDLE -> CognitivePalette.agentIdle
        AgentActivityState.ACTIVE -> CognitivePalette.agentActive
        AgentActivityState.PROCESSING -> CognitivePalette.agentProcessing
        AgentActivityState.COMPLETE -> CognitivePalette.agentComplete
        AgentActivityState.SPAWNING -> CognitivePalette.agentIdle
    }

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
                val df = depthFactor(depth)
                drawAgentNode(
                    this, cx, cy,
                    nodeRadius(baseRadius, df),
                    ringAlpha(df, agent.cognitivePhase),
                    fillAlpha(df),
                    agent
                )
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
            drawCircle(
                color = colorForState(agent.state),
                radius = nodeRadius,
                center = Offset(cx, cy),
                alpha = fillAlpha
            )

            // Processing shimmer (pulse phase)
            if (agent.state == AgentActivityState.PROCESSING) {
                drawCircle(
                    color = Color.White,
                    radius = nodeRadius * 0.8f,
                    center = Offset(cx, cy),
                    alpha = shimmerAlpha(agent.pulsePhase) * fillAlpha
                )
            }
        }
    }
}
