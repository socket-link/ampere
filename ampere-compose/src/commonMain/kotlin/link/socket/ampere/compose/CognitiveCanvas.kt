package link.socket.ampere.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.particle.ParticleSystem
import link.socket.ampere.animation.substrate.SubstrateState

/**
 * Primary composable for rendering AMPERE's cognitive visualization.
 *
 * Composites all animation layers onto a Compose Canvas:
 * - Layer 0: Substrate density field (background)
 * - Layer 1: Flow connections between agents
 * - Layer 2: Particles (events, trails, ambient)
 * - Layer 3: Agent nodes
 *
 * The same animation state that drives the terminal TUI is rendered here
 * through Compose Canvas/DrawScope — same brain, different eyes.
 *
 * @param substrate Current substrate state
 * @param particles Current particle system
 * @param agents Current agent layer
 * @param flow Current flow layer (optional)
 * @param modifier Compose modifier
 */
@Composable
fun CognitiveCanvas(
    substrate: SubstrateState,
    particles: ParticleSystem,
    agents: AgentLayer,
    flow: FlowLayer? = null,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val cellWidth = size.width / substrate.width
        val cellHeight = size.height / substrate.height

        // Layer 0: Substrate density field
        SubstrateCanvasRenderer.render(this, substrate, cellWidth, cellHeight)

        // Layer 1: Flow connections between agents
        flow?.let {
            FlowCanvasRenderer.render(this, it, cellWidth, cellHeight)
        }

        // Layer 2: Particles
        ParticleCanvasRenderer.render(this, particles, cellWidth, cellHeight)

        // Layer 3: Agent nodes
        AgentCanvasRenderer.render(this, agents, cellWidth, cellHeight)
    }
}
