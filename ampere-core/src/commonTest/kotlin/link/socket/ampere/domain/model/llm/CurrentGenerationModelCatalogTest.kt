package link.socket.ampere.domain.model.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModelFeatures.ThinkingLevel
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.limits.TokenCount

/**
 * Pins the current-generation catalog entries (AMPR-326, AMPR-488): the model IDs
 * Socket's routing catalog resolves by name, which of them must not be sent
 * `temperature` / `top_p`, and which take a `reasoning_effort`.
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
            // The October 2026 generation (AMPR-488).
            "claude-haiku-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5-5",
            "gemini-4-argon",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gpt-6-sol",
            "gpt-6-luna",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
        )
        assertEquals(emptySet(), expected - names)
    }

    @Test
    fun `retired model ids are not bundled`() {
        // A model in the catalog is one the relay may route to, and routing to a
        // retired ID can only fail. `gemini-3-pro-latest` never existed at all.
        val retired = setOf(
            "gemini-3-pro-latest",
            "claude-3-7-sonnet-latest",
            "claude-3-5-haiku-latest",
            "claude-3-haiku-20240307",
            "claude-sonnet-4-0",
            "claude-opus-4-0",
            "claude-opus-4-1",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gpt-5.1-chat-latest",
            "gpt-5.1-codex-max",
            // Shut down 2026-10-23.
            "o4-mini",
        )
        assertEquals(emptySet(), allModels.map { it.name }.toSet() intersect retired)
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
            AIModel_OpenAI.GPT_5_5,
            AIModel_OpenAI.GPT_5_6_Sol,
            // Documented as rejecting temperature outright.
            AIModel_OpenAI.GPT_5,
            AIModel_OpenAI.GPT_5_mini,
            AIModel_OpenAI.GPT_5_nano,
            AIModel_Claude.Opus_5_5,
            AIModel_Claude.Sonnet_5_5,
            AIModel_Claude.Haiku_5_5,
            AIModel_OpenAI.GPT_6_Sol,
            AIModel_OpenAI.GPT_6_Luna,
            AIModel_OpenAI.GPT_5_6_Terra,
            AIModel_OpenAI.GPT_5_6_Luna,
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
            AIModel_Gemini.Pro_2_5,
            AIModel_Gemini.Flash_2_5,
            AIModel_Gemini.Flash_Lite_2_5,
            // Default reasoning effort is `none`, where OpenAI accepts sampling.
            AIModel_OpenAI.GPT_5_4,
            AIModel_OpenAI.GPT_5_4_mini,
        )
        accepting.forEach { model ->
            assertTrue(model.features.supportsSamplingParameters, "${model.name} accepts sampling parameters")
        }
    }

    @Test
    fun `every gemini 3 and later model leaves sampling at the provider default`() {
        // Google: lowering temperature below 1.0 on Gemini 3 "may lead to
        // unexpected behavior, such as looping or degraded performance."
        val gemini3OrLater = AIModel_Gemini.ALL_MODELS.filterNot { it.name.startsWith("gemini-2.") }
        assertEquals(
            setOf(
                "gemini-3.1-pro-preview",
                "gemini-3.8-flash",
                "gemini-3.6-flash",
                "gemini-3.5-flash-lite",
                "gemini-4-argon",
            ),
            gemini3OrLater.map { it.name }.toSet(),
        )
        gemini3OrLater.forEach { model ->
            assertFalse(model.features.supportsSamplingParameters, "${model.name} must not be sent temperature")
        }
    }

    @Test
    fun `gemini 3 and later and openai from gpt-5_5 name the thinking level they default to and nothing else does`() {
        val expected = mapOf(
            "gemini-3.1-pro-preview" to ThinkingLevel.HIGH,
            "gemini-3.8-flash" to ThinkingLevel.MEDIUM,
            "gemini-3.6-flash" to ThinkingLevel.MEDIUM,
            "gemini-3.5-flash-lite" to ThinkingLevel.MINIMAL,
            "gemini-4-argon" to ThinkingLevel.MEDIUM,
            // AMPR-501: each model page documents `medium` as the default.
            "gpt-5.5" to ThinkingLevel.MEDIUM,
            "gpt-5.6-sol" to ThinkingLevel.MEDIUM,
            "gpt-6-sol" to ThinkingLevel.MEDIUM,
            "gpt-6-luna" to ThinkingLevel.MEDIUM,
            "gpt-5.6-terra" to ThinkingLevel.MEDIUM,
            "gpt-5.6-luna" to ThinkingLevel.MEDIUM,
        )
        val actual = allModels
            .mapNotNull { model -> model.features.defaultThinking?.let { model.name to it } }
            .toMap()
        assertEquals(expected, actual)
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
            AIModel_Claude.Opus_5_5,
            AIModel_Claude.Sonnet_5_5,
            AIModel_Claude.Haiku_5_5,
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
