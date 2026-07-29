package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptTokenEstimatorTest {

    @Test
    fun `empty message list estimates zero tokens`() {
        assertEquals(0, PromptTokenEstimator.estimateInputTokens(emptyList()))
    }

    @Test
    fun `an empty message still costs its framing overhead`() {
        assertEquals(4, PromptTokenEstimator.estimateInputTokens(listOf("")))
    }

    @Test
    fun `content is charged at four characters per token and rounded up`() {
        // 8 chars -> 2 content tokens + 4 framing
        assertEquals(6, PromptTokenEstimator.estimateInputTokens(listOf("12345678")))
        // 9 chars -> ceil(9/4) = 3 content tokens + 4 framing
        assertEquals(7, PromptTokenEstimator.estimateInputTokens(listOf("123456789")))
    }

    @Test
    fun `each message is charged separately`() {
        val single = PromptTokenEstimator.estimateInputTokens(listOf("abcdabcd"))
        val split = PromptTokenEstimator.estimateInputTokens(listOf("abcd", "abcd"))

        assertEquals(6, single)
        assertEquals(10, split)
    }

    @Test
    fun `estimate is monotonic in prompt length`() {
        val short = PromptTokenEstimator.estimateInputTokens(listOf("a".repeat(100)))
        val long = PromptTokenEstimator.estimateInputTokens(listOf("a".repeat(1_000)))

        assertTrue(long > short)
    }

    @Test
    fun `estimate is deterministic for the same input`() {
        val messages = listOf("You are a helpful agent.", "Summarize the following text.")

        assertEquals(
            PromptTokenEstimator.estimateInputTokens(messages),
            PromptTokenEstimator.estimateInputTokens(messages),
        )
    }
}
