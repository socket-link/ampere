package link.socket.ampere.domain.agent.bundled

import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

private const val NAME = "On-Device Assistant"
private const val DESCRIPTION =
    "Short-form assistant sized for an on-device model: answers, summaries, " +
        "rewrites and classifications that fit a small context window"

private val PROMPT = """
    You are an assistant running on the user's own device.

    You should:
    - Answer directly and briefly. A few sentences is usually right.
    - Say so plainly when you do not know, rather than guessing.

    You must:
    - Stay within what the user asked. Do not invent tasks, files, or follow-up work.
""".trimIndent()

/**
 * The agent definition behind
 * [OnDeviceInferenceSession][link.socket.ampere.llm.OnDeviceInferenceSession]
 * (AMPR-327): the one bundled definition whose declared floor is
 * [CapabilityRung.ZERO], which is what makes the on-device model an eligible
 * route for its calls at all.
 *
 * A floor is a minimum, so this does not *pin* the work to the device. The relay
 * still chooses the cheapest capable model: the on-device one when the engine
 * reports it available (it is free, so it always wins on price), and the
 * cheapest cloud model otherwise. Agents that declare a higher floor — the code
 * agent's is `THREE` — are never routed on-device, by the same rule.
 *
 * Deliberately absent from [agentList]: that list feeds the conversation UI,
 * which calls a provider's HTTP client directly, and this definition's suggested
 * configuration has no HTTP endpoint to call.
 */
data object OnDeviceAssistantAgent : AgentDefinition.Bundled(
    name = NAME,
    description = DESCRIPTION,
    prompt = PROMPT,
    suggestedAIConfigurationBuilder = {
        AIConfiguration_Default(
            provider = AIProvider_OnDevice,
            model = AIModel_OnDevice.AppleFoundationModels,
        )
    },
) {
    override val minimumRung: CapabilityRung = CapabilityRung.ZERO
}
