package link.socket.ampere.agents.domain.routing.local

import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload

/**
 * Test [LocalInferenceEngine] that records the prompts it is asked to generate
 * and returns a scripted [Result]. Stands in for the per-platform engine bound
 * in `:ampere-relay-local-*` modules (Phase 2).
 *
 * [respondStructured] is `null` by default, so [generateStructured] exercises
 * the interface's own default (Prose-only) unless a test opts into scripting a
 * genuine structured response — e.g. to stand in for Foundation Models' guided
 * generation (AMPR-225).
 *
 * [countTokens] is `null` by default, so the engine cannot count and a caller
 * sizes its prompt by the estimate, as before AMPR-460. A test that stands in
 * for an engine with a tokenizer scripts a count; one that throws stands in
 * for an engine whose counter fails.
 */
class FakeLocalInferenceEngine(
    private val capacity: LocalCapacity = LocalCapacity(available = true, modelId = "fake-local"),
    private val respond: (prompt: String) -> Result<String> = { Result.success("LOCAL::$it") },
    private val respondStructured: ((kind: EmissionKind, prompt: String) -> Result<EmissionPayload>)? = null,
    private val countTokens: ((text: String) -> Int?)? = null,
) : LocalInferenceEngine {

    var probeCount: Int = 0
        private set
    var generateCount: Int = 0
        private set
    var lastPrompt: String? = null
        private set
    var structuredGenerateCount: Int = 0
        private set
    var lastRequestedKind: EmissionKind? = null
        private set
    var tokenCountCount: Int = 0
        private set
    var lastCountedText: String? = null
        private set

    override suspend fun probe(): LocalCapacity {
        probeCount++
        return capacity
    }

    override suspend fun generate(prompt: String): Result<String> {
        generateCount++
        lastPrompt = prompt
        return respond(prompt)
    }

    override suspend fun tokenCount(text: String): Int? {
        val scripted = countTokens ?: return super.tokenCount(text)
        tokenCountCount++
        lastCountedText = text
        return scripted(text)
    }

    override suspend fun generateStructured(kind: EmissionKind, prompt: String): Result<EmissionPayload> {
        val scripted = respondStructured ?: return super.generateStructured(kind, prompt)
        structuredGenerateCount++
        lastRequestedKind = kind
        return scripted(kind, prompt)
    }
}
