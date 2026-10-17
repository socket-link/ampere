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
 * The display-text identity: the agent definition behind
 * [OnDeviceInferenceSession][link.socket.ampere.llm.OnDeviceInferenceSession]
 * (AMPR-327), and the one bundled definition whose declared floor is
 * [CapabilityRung.ZERO], which is what makes the on-device model an eligible
 * route for its calls at all.
 *
 * Rung ZERO serves display text only — words a person reads, which nothing in
 * the run acts on, decides on or remembers (AMPR-372). That is this agent's whole
 * job: an answer, a summary, a rewrite or a label that a person is shown. It does
 * not run the PROPEL loop, and no definition that does may declare ZERO; every
 * phase call and every judgment stays at `ONE` or above. The measurement behind
 * the rule, on the production engine path with the real builders' prompts, is
 * `docs/ampr-372-rung-zero-eligibility.md`: single-source text was sound 33 of 33
 * times, while anything selected, compared, counted or attributed across items
 * was sound 5 of 33 — so a host should hand this agent single-source text.
 *
 * A floor is a minimum, so this does not *pin* the work to the device. The relay
 * still chooses the cheapest capable model: the on-device one when the engine
 * reports it available (it is free, so it always wins on price), and the
 * cheapest *rated* cloud model otherwise. Agents that declare a higher floor —
 * the code agent's is `THREE` — are never routed on-device, by the same rule.
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
