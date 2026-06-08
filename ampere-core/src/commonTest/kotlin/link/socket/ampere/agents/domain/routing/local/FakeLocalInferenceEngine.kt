package link.socket.ampere.agents.domain.routing.local

/**
 * Test [LocalInferenceEngine] that records the prompts it is asked to generate
 * and returns a scripted [Result]. Stands in for the per-platform engine bound
 * in `:ampere-relay-local-*` modules (Phase 2).
 */
class FakeLocalInferenceEngine(
    private val capacity: LocalCapacity = LocalCapacity(available = true, modelId = "fake-local"),
    private val respond: (prompt: String) -> Result<String> = { Result.success("LOCAL::$it") },
) : LocalInferenceEngine {

    var probeCount: Int = 0
        private set
    var generateCount: Int = 0
        private set
    var lastPrompt: String? = null
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
}
