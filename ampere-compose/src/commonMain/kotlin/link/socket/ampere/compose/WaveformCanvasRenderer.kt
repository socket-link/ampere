package link.socket.ampere.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.projection.Camera
import link.socket.ampere.animation.projection.ScreenProjector
import link.socket.ampere.animation.waveform.CognitiveWaveform
import kotlin.math.sqrt

/**
 * Renders the [CognitiveWaveform] as a colored gradient mesh on a Compose Canvas.
 *
 * Height maps to brightness (higher peaks are brighter), and the nearest
 * agent's cognitive phase maps to hue — creating a subtle, ambient landscape
 * behind the agent nodes. Think of it as a heat-map of cognitive activity
 * rendered as a smooth surface.
 */
object WaveformCanvasRenderer {

    /** Maximum alpha for the gradient mesh to keep it as a subtle background. */
    private const val MAX_ALPHA = 0.35f

    /** Minimum alpha — even flat regions get a hint of color. */
    private const val MIN_ALPHA = 0.03f

    /**
     * Render the waveform surface as a gradient mesh.
     *
     * @param drawScope The Compose DrawScope to render into
     * @param waveform The cognitive waveform heightmap
     * @param agents Agent layer for phase-based coloring
     * @param camera Camera for 3D-to-2D projection
     */
    fun render(
        drawScope: DrawScope,
        waveform: CognitiveWaveform,
        agents: AgentLayer,
        camera: Camera
    ) {
        val canvasWidth = drawScope.size.width
        val canvasHeight = drawScope.size.height

        val projector = ScreenProjector(
            screenWidth = canvasWidth.toInt().coerceAtLeast(1),
            screenHeight = canvasHeight.toInt().coerceAtLeast(1),
            charAspect = 1.0f
        )

        // Compute height range for normalization
        var minHeight = Float.MAX_VALUE
        var maxHeight = Float.MIN_VALUE
        for (gz in 0 until waveform.gridDepth) {
            for (gx in 0 until waveform.gridWidth) {
                val h = waveform.heightAt(gx, gz)
                if (h < minHeight) minHeight = h
                if (h > maxHeight) maxHeight = h
            }
        }
        val heightRange = (maxHeight - minHeight).coerceAtLeast(0.01f)

        // Pre-compute agent positions for phase lookup
        val agentPositions = agents.allAgents.map { agent ->
            Triple(agent.position.x, agent.position.y, agent.cognitivePhase)
        }

        // Render each grid cell as a colored rectangle
        with(drawScope) {
            for (gz in 0 until waveform.gridDepth) {
                for (gx in 0 until waveform.gridWidth) {
                    val worldPos = waveform.worldPosition(gx, gz)
                    val (screenX, screenY, depth) = projector.projectContinuous(worldPos, camera)

                    // Skip points outside the visible area
                    if (depth !in 0f..1f) continue
                    if (screenX < -canvasWidth * 0.1f || screenX > canvasWidth * 1.1f) continue
                    if (screenY < -canvasHeight * 0.1f || screenY > canvasHeight * 1.1f) continue

                    // Normalized height (0 = lowest, 1 = highest)
                    val normalizedHeight = ((waveform.heightAt(gx, gz) - minHeight) / heightRange)
                        .coerceIn(0f, 1f)

                    // Find dominant phase at this position (nearest agent)
                    val phase = dominantPhaseAt(
                        worldPos.x, worldPos.z, agentPositions
                    )
                    val baseColor = CognitivePalette.forPhase(phase)

                    // Height → brightness: higher peaks are brighter
                    val alpha = (MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * normalizedHeight)

                    // Depth-based fade: farther cells are dimmer
                    val depthFade = 1f - depth.coerceIn(0f, 1f) * 0.5f

                    // Cell size in screen space — approximate from grid resolution
                    val cellScreenWidth = canvasWidth / waveform.gridWidth * 1.2f
                    val cellScreenHeight = canvasHeight / waveform.gridDepth * 1.2f

                    drawRect(
                        color = baseColor,
                        topLeft = Offset(screenX - cellScreenWidth / 2, screenY - cellScreenHeight / 2),
                        size = Size(cellScreenWidth, cellScreenHeight),
                        alpha = (alpha * depthFade).coerceIn(0f, 1f)
                    )
                }
            }
        }
    }

    /**
     * Find the dominant cognitive phase at a world position based on
     * inverse-distance weighting from nearby agents.
     */
    private fun dominantPhaseAt(
        worldX: Float,
        worldZ: Float,
        agentPositions: List<Triple<Float, Float, CognitivePhase>>
    ): CognitivePhase {
        if (agentPositions.isEmpty()) return CognitivePhase.NONE

        var bestPhase = CognitivePhase.NONE
        var bestWeight = 0f

        val phaseWeights = mutableMapOf<CognitivePhase, Float>()

        for ((ax, az, phase) in agentPositions) {
            val dx = worldX - ax
            val dz = worldZ - az
            val dist = sqrt(dx * dx + dz * dz)

            // Smooth falloff: weight = 1 / (1 + dist^2)
            val weight = 1f / (1f + dist * dist * 0.1f)
            phaseWeights[phase] = (phaseWeights[phase] ?: 0f) + weight
        }

        for ((phase, weight) in phaseWeights) {
            if (weight > bestWeight) {
                bestWeight = weight
                bestPhase = phase
            }
        }

        return bestPhase
    }
}
