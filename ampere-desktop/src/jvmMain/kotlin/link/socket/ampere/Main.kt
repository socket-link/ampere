package link.socket.ampere

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.awt.Dimension
import kotlinx.coroutines.delay
import link.socket.ampere.cli.animation.choreography.CognitiveChoreographer
import link.socket.ampere.compose.CognitiveCanvas
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.field.FlowLayer
import link.socket.phosphor.field.ParticleSystem
import link.socket.phosphor.field.SubstrateAnimator
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Ampere \u2014 Cognitive Engine",
            state =
                WindowState(
                    width = 1200.dp,
                    height = 800.dp,
                ),
        ) {
            window.minimumSize = Dimension(800, 600)
            cognitiveDemoScreen()
        }
    }

@Composable
fun cognitiveDemoScreen() {
    val substrate = remember { mutableStateOf(SubstrateState.create(80, 40, 0.2f)) }
    val particles = remember { ParticleSystem(maxParticles = 200) }
    val agents = remember { AgentLayer(80, 40, AgentLayoutOrientation.CIRCULAR) }
    val flow = remember { FlowLayer(80, 40) }
    val animator = remember { SubstrateAnimator(baseDensity = 0.2f) }
    val choreographer = remember { CognitiveChoreographer(particles, animator) }

    LaunchedEffect(Unit) {
        agents.addAgent(
            AgentVisualState(
                id = "spark",
                name = "Spark",
                role = "reasoning",
                position = Vector2(20f, 20f),
                state = AgentActivityState.PROCESSING,
                cognitivePhase = CognitivePhase.PERCEIVE,
            ),
        )
        agents.addAgent(
            AgentVisualState(
                id = "jazz",
                name = "Jazz",
                role = "codegen",
                position = Vector2(60f, 20f),
                state = AgentActivityState.IDLE,
            ),
        )
        agents.relayout()
        flow.createConnectionsFromAgents(agents, listOf("spark" to "jazz"))
    }

    LaunchedEffect(Unit) {
        val dt = 0.033f
        while (true) {
            delay(33)
            substrate.value = animator.updateAmbient(substrate.value, dt)
            substrate.value = choreographer.update(agents, substrate.value, dt)
            particles.update(dt)
            agents.update(dt)
            flow.update(dt)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E))) {
        CognitiveCanvas(
            substrate = substrate.value,
            particles = particles,
            agents = agents,
            flow = flow,
        )
    }
}
