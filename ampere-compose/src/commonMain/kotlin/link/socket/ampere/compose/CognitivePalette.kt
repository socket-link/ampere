package link.socket.ampere.compose

import androidx.compose.ui.graphics.Color
import link.socket.phosphor.color.CognitiveColorModel
import link.socket.phosphor.color.FlowColorState
import link.socket.phosphor.color.ParticleColorKind
import link.socket.phosphor.renderer.ComposeColor
import link.socket.phosphor.renderer.ComposeColorAdapter
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Color palette for Compose rendering sourced from Phosphor's neutral color model.
 */
object CognitivePalette {
    private val colorModel = CognitiveColorModel
    private val composeColorAdapter = ComposeColorAdapter()

    private fun toComposeColor(color: ComposeColor): Color = Color(
        red = color.red / 255f,
        green = color.green / 255f,
        blue = color.blue / 255f,
        alpha = color.alpha
    )

    // Substrate
    val substrateDim = toComposeColor(composeColorAdapter.adapt(colorModel.flowIntensityRamp.sample(0.2f)))
    val substrateMid = toComposeColor(composeColorAdapter.adapt(colorModel.flowIntensityRamp.sample(0.55f)))
    val substrateBright = toComposeColor(composeColorAdapter.adapt(colorModel.flowIntensityRamp.sample(0.9f)))

    // Agents
    val agentIdle = toComposeColor(composeColorAdapter.adapt(colorModel.agentActivityColors.getValue(AgentActivityState.IDLE)))
    val agentActive = toComposeColor(composeColorAdapter.adapt(colorModel.agentActivityColors.getValue(AgentActivityState.ACTIVE)))
    val agentProcessing = toComposeColor(composeColorAdapter.adapt(colorModel.agentActivityColors.getValue(AgentActivityState.PROCESSING)))
    val agentComplete = toComposeColor(composeColorAdapter.adapt(colorModel.agentActivityColors.getValue(AgentActivityState.COMPLETE)))

    // Flow
    val flowDormant = toComposeColor(composeColorAdapter.adapt(colorModel.flowStateColors.getValue(FlowColorState.DORMANT)))
    val flowActive = toComposeColor(composeColorAdapter.adapt(colorModel.flowStateColors.getValue(FlowColorState.ACTIVATING)))
    val flowToken = toComposeColor(composeColorAdapter.adapt(colorModel.particleColors.getValue(ParticleColorKind.TRAIL)))

    // Accents
    val sparkAccent = toComposeColor(composeColorAdapter.adapt(colorModel.particleColors.getValue(ParticleColorKind.SPARK)))
    val logoBolt = toComposeColor(composeColorAdapter.adapt(colorModel.roleColorFor("reasoning")))
    val logoText = toComposeColor(composeColorAdapter.adapt(colorModel.roleColorFor("coordinator")))

    // Cognitive phase colors
    val perceive = toComposeColor(composeColorAdapter.adapt(colorModel.phaseColorFor(CognitivePhase.PERCEIVE)))
    val recall = toComposeColor(composeColorAdapter.adapt(colorModel.phaseColorFor(CognitivePhase.RECALL)))
    val plan = toComposeColor(composeColorAdapter.adapt(colorModel.phaseColorFor(CognitivePhase.PLAN)))
    val execute = toComposeColor(composeColorAdapter.adapt(colorModel.phaseColorFor(CognitivePhase.EXECUTE)))
    val evaluate = toComposeColor(composeColorAdapter.adapt(colorModel.phaseColorFor(CognitivePhase.EVALUATE)))
    val loop = toComposeColor(composeColorAdapter.adapt(colorModel.phaseColorFor(CognitivePhase.LOOP)))

    fun forDensity(density: Float): Color = when {
        density < 0.3f -> substrateDim
        density < 0.6f -> substrateMid
        else -> substrateBright
    }

    fun forPhase(phase: CognitivePhase): Color = when (phase) {
        CognitivePhase.PERCEIVE -> perceive
        CognitivePhase.RECALL -> recall
        CognitivePhase.PLAN -> plan
        CognitivePhase.EXECUTE -> execute
        CognitivePhase.EVALUATE -> evaluate
        CognitivePhase.LOOP -> loop
        CognitivePhase.NONE -> agentIdle
    }
}
