package link.socket.ampere.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.particle.ParticleSystem
import link.socket.ampere.animation.projection.Camera
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.waveform.CognitiveWaveform

/**
 * Primary composable for rendering AMPERE's cognitive visualization.
 *
 * Composites all animation layers onto a Compose Canvas:
 * - Layer 0: Substrate density field (background)
 * - Layer 0.5: Waveform gradient mesh (optional, when waveform + camera provided)
 * - Layer 1: Flow connections between agents
 * - Layer 2: Particles (events, trails, ambient)
 * - Layer 3: Agent nodes (depth-sorted when camera provided)
 *
 * The same animation state that drives the terminal TUI is rendered here
 * through Compose Canvas/DrawScope — same brain, different eyes.
 *
 * @param substrate Current substrate state
 * @param particles Current particle system
 * @param agents Current agent layer
 * @param flow Current flow layer (optional)
 * @param camera Camera for 3D depth projection (optional). When provided,
 *   agents are depth-sorted and scaled by distance, and the waveform
 *   gradient mesh is rendered as a background surface.
 * @param waveform Cognitive waveform for gradient mesh rendering (optional).
 *   Only rendered when both camera and waveform are provided.
 * @param modifier Compose modifier
 */
@Composable
fun CognitiveCanvas(
    substrate: SubstrateState,
    particles: ParticleSystem,
    agents: AgentLayer,
    flow: FlowLayer? = null,
    camera: Camera? = null,
    waveform: CognitiveWaveform? = null,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val cellWidth = size.width / substrate.width
        val cellHeight = size.height / substrate.height

        // Layer 0: Substrate density field
        SubstrateCanvasRenderer.render(this, substrate, cellWidth, cellHeight)

        // Layer 0.5: Waveform gradient mesh (3D surface as colored background)
        if (waveform != null && camera != null) {
            WaveformCanvasRenderer.render(this, waveform, agents, camera)
        }

        // Layer 1: Flow connections between agents
        flow?.let {
            FlowCanvasRenderer.render(this, it, cellWidth, cellHeight)
        }

        // Layer 2: Particles
        ParticleCanvasRenderer.render(this, particles, cellWidth, cellHeight)

        // Layer 3: Agent nodes (depth-projected when camera available)
        AgentCanvasRenderer.render(this, agents, cellWidth, cellHeight, camera)
    }
}
