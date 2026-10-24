package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.ThinkingLevel
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini

/** Pins the `reasoning_effort` a call sends to a thinking Gemini model (AMPR-488). */
class ReasoningEffortPolicyTest {

    private val low = ThinkingLevel.LOW
    private val medium = ThinkingLevel.MEDIUM
    private val high = ThinkingLevel.HIGH

    @Test
    fun `the PROPEL caps all send low`() {
        // PERCEIVE asks for 500 tokens; PLAN and the outcome evaluation for 1000.
        listOf(500, 1000, ReasoningEffortPolicy.LOW_EFFORT_MAX_TOKENS).forEach { cap ->
            listOf(AIModel_Gemini.Flash_3_8, AIModel_Gemini.Argon_4, AIModel_Gemini.Pro_3_1_Preview).forEach { model ->
                assertEquals(low, ReasoningEffortPolicy.effortFor(model, cap, null), "${model.name} at $cap")
            }
        }
    }

    @Test
    fun `a small budget caps an explicit ask for high reasoning`() {
        val context = RoutingContext(preferredReasoning = RelativeReasoning.HIGH)

        assertEquals(low, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Argon_4, 500, context))
        assertEquals(medium, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Argon_4, 4000, context))
        assertEquals(high, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Argon_4, 16_000, context))
    }

    @Test
    fun `an unhinted call above the low cap gets the model default or the budget cap`() {
        assertEquals(medium, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Flash_3_8, 4000, null))
        assertEquals(medium, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Flash_3_8, 16_000, null))
        // 3.1 Pro defaults to high, which a 4000 cap cannot afford.
        assertEquals(medium, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Pro_3_1_Preview, 4000, null))
        assertEquals(high, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Pro_3_1_Preview, 16_000, null))
    }

    @Test
    fun `an ask for low reasoning lowers a large budget call`() {
        val preferred = RoutingContext(preferredReasoning = RelativeReasoning.LOW)
        val required = RoutingContext(requirements = CapabilityRequirement(minReasoning = RelativeReasoning.LOW))

        assertEquals(low, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Pro_3_1_Preview, 16_000, preferred))
        assertEquals(low, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Pro_3_1_Preview, 16_000, required))
    }

    @Test
    fun `the higher of the two hints is the ask`() {
        val context = RoutingContext(
            preferredReasoning = RelativeReasoning.LOW,
            requirements = CapabilityRequirement(minReasoning = RelativeReasoning.HIGH),
        )

        assertEquals(high, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Flash_3_8, 16_000, context))
    }

    @Test
    fun `a capability rung does not move the effort`() {
        val context = RoutingContext(requirements = CapabilityRequirement(minRung = CapabilityRung.FOUR))

        assertEquals(medium, ReasoningEffortPolicy.effortFor(AIModel_Gemini.Flash_3_8, 16_000, context))
    }

    @Test
    fun `a model that defaults to minimal thinking is sent no effort unless asked`() {
        // gemini-3.5-flash-lite thinks at `minimal` by default; Ampere never names it.
        assertNull(ReasoningEffortPolicy.effortFor(AIModel_Gemini.Flash_Lite_3_5, 500, null))
        assertNull(ReasoningEffortPolicy.effortFor(AIModel_Gemini.Flash_Lite_3_5, 16_000, null))
        assertEquals(
            low,
            ReasoningEffortPolicy.effortFor(
                AIModel_Gemini.Flash_Lite_3_5,
                500,
                RoutingContext(preferredReasoning = RelativeReasoning.HIGH),
            ),
        )
    }

    @Test
    fun `levels go out in lower case`() {
        assertEquals(listOf("low", "medium", "high"), listOf(low, medium, high).map(ReasoningEffortPolicy::wireValue))
    }

    @Test
    fun `models that take no effort from ampere are sent none`() {
        listOf(AIModel_Gemini.Flash_2_5, AIModel_Claude.Opus_5_5).forEach { model ->
            assertNull(ReasoningEffortPolicy.effortFor(model, 500, null), model.name)
        }
    }
}
