package link.socket.ampere.domain.model.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.limits.TokenCount

/**
 * Pins the current-generation catalog entries (AMPR-326): the model IDs Socket's
 * routing catalog resolves by name, and which of them must not be sent
 * `temperature` / `top_p`.
 */
class CurrentGenerationModelCatalogTest {

    private val allModels: List<AIModel> =
        AIModel_Claude.ALL_MODELS + AIModel_OpenAI.ALL_MODELS + AIModel_Gemini.ALL_MODELS

    @Test
    fun `current generation model ids are bundled`() {
        val names = allModels.map { it.name }.toSet()
        val expected = setOf(
            "claude-fable-5-1",
            "claude-opus-5",
            "claude-opus-4-8",
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-sonnet-5",
            "claude-sonnet-4-6",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.5",
            "gpt-5.6-sol",
            "gemini-3.1-pro-preview",
            "gemini-3.8-flash",
        )
        assertEquals(emptySet(), expected - names)
    }

    @Test
    fun `gemini-3-pro-latest is no longer bundled`() {
        // Google never served this ID; routing to it could only fail.
        assertFalse(allModels.any { it.name == "gemini-3-pro-latest" })
    }

    @Test
    fun `bundled model names are unique`() {
        val duplicates = allModels.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        assertEquals(emptySet(), duplicates)
    }

    @Test
    fun `models that reject sampling parameters are flagged`() {
        val rejecting = listOf(
            AIModel_Claude.Opus_4_7,
            AIModel_Claude.Opus_4_8,
            AIModel_Claude.Opus_5,
            AIModel_Claude.Sonnet_5,
            AIModel_Claude.Fable_5_1,
            AIModel_OpenAI.GPT_5_4,
            AIModel_OpenAI.GPT_5_4_mini,
            AIModel_OpenAI.GPT_5_5,
            AIModel_OpenAI.GPT_5_6_Sol,
        )
        rejecting.forEach { model ->
            assertFalse(model.features.supportsSamplingParameters, "${model.name} rejects sampling parameters")
        }
    }

    @Test
    fun `models that accept sampling parameters keep them`() {
        val accepting = listOf(
            AIModel_Claude.Opus_4_6,
            AIModel_Claude.Sonnet_4_6,
            AIModel_Claude.Sonnet_4_5,
            AIModel_Claude.Haiku_4_5,
            AIModel_Gemini.Pro_3_1_Preview,
            AIModel_Gemini.Flash_3_8,
        )
        accepting.forEach { model ->
            assertTrue(model.features.supportsSamplingParameters, "${model.name} accepts sampling parameters")
        }
    }

    @Test
    fun `current generation claude models have a 1M context window and 128K output`() {
        listOf(
            AIModel_Claude.Opus_4_6,
            AIModel_Claude.Opus_4_7,
            AIModel_Claude.Opus_4_8,
            AIModel_Claude.Opus_5,
            AIModel_Claude.Sonnet_4_6,
            AIModel_Claude.Sonnet_5,
            AIModel_Claude.Fable_5_1,
        ).forEach { model ->
            assertEquals(TokenCount._1m, model.limits.token.contextWindow, model.name)
            assertEquals(TokenCount._128k, model.limits.token.maxOutput, model.name)
        }
    }

    @Test
    fun `custom models carry the id they were built with and stay out of ALL_MODELS`() {
        val template = AIModel_Claude.Opus_5
        val custom = AIModel_Claude.Custom(
            name = "claude-opus-5-1",
            features = template.features,
            limits = template.limits,
        )

        assertEquals("claude-opus-5-1", custom.name)
        assertEquals("claude-opus-5-1", custom.displayName)
        assertFalse(AIModel_Claude.ALL_MODELS.any { it.name == custom.name })
        assertEquals(
            custom,
            AIModel_Claude.Custom(name = "claude-opus-5-1", features = template.features, limits = template.limits),
        )
    }

    @Test
    fun `custom models exist for every cloud provider`() {
        val openAi: AIModel_OpenAI = AIModel_OpenAI.Custom(
            name = "gpt-6-astra",
            features = AIModel_OpenAI.GPT_5_5.features,
            limits = AIModel_OpenAI.GPT_5_5.limits,
        )
        val gemini: AIModel_Gemini = AIModel_Gemini.Custom(
            name = "gemini-3.9-flash",
            features = AIModel_Gemini.Flash_3_8.features,
            limits = AIModel_Gemini.Flash_3_8.limits,
        )

        assertEquals("gpt-6-astra", openAi.name)
        assertEquals("gemini-3.9-flash", gemini.name)
    }
}
