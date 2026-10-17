package link.socket.ampere.agents.domain.routing.local

import kotlinx.coroutines.CancellationException
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.emission.ProseFormat

/**
 * Swift-facing counterpart to [LocalInferenceEngine] (AMPR-225): plain
 * throwing suspend functions instead of [Result]-returning ones.
 *
 * Kotlin's `Result<T>` is not constructible from Swift — Kotlin/Native's
 * Objective-C export erases it to an opaque `id` with no Swift-visible
 * factory (confirmed against the generated header: no `Result` wrapper type
 * is exported). A Swift class implementing [LocalInferenceEngine] directly
 * would therefore have no way to produce a `Result.success`/`Result.failure`
 * to return. Subclassing this instead — throwing on failure, as ordinary
 * Swift `async throws` code already does — lets Kotlin/Native's `@Throws`
 * export bridge the failure into a catchable `NSError` on the Swift side.
 * [toLocalInferenceEngine] adapts the throwing contract back to the
 * [Result]-typed [LocalInferenceEngine] the rest of Ampere depends on.
 */
abstract class SwiftLocalInferenceEngine {

    @Throws(Exception::class)
    abstract suspend fun probe(): LocalCapacity

    @Throws(Exception::class)
    abstract suspend fun generate(prompt: String): String

    /**
     * Mirrors [LocalInferenceEngine.generate] for a [LocalGenerationRequest]
     * (AMPR-462): the system message, the user's turn, the output budget and
     * the temperature, apart. Override it to hand them to a framework that
     * takes them separately, as Apple's engine does.
     *
     * The default is the Kotlin contract's: it hands [generate] the request's
     * [flattenedPrompt][LocalGenerationRequest.flattenedPrompt], so a Swift
     * engine written before this method compiles unchanged and receives the
     * prompt it always did.
     */
    @Throws(Exception::class)
    open suspend fun generate(request: LocalGenerationRequest): String = generate(request.flattenedPrompt)

    /**
     * Mirrors [LocalInferenceEngine.tokenCount]: the engine's own count of
     * [text], or `nil` when it cannot count (AMPR-460). Boxed because a Kotlin
     * `Int?` crosses the Objective-C export as an optional `KotlinInt`, which
     * Swift can return. A throw is adapted to "cannot count", never to a
     * failed call.
     */
    @Throws(Exception::class)
    open suspend fun tokenCount(text: String): Int? = null

    /** Mirrors [LocalInferenceEngine.generateStructured]'s own Prose-only default. */
    @Throws(Exception::class)
    open suspend fun generateStructured(kind: EmissionKind, prompt: String): EmissionPayload =
        if (kind == EmissionKind.Prose) {
            EmissionPayload.Prose(text = generate(prompt), format = ProseFormat.PLAIN)
        } else {
            throw UnsupportedOperationException(
                "This engine does not support structured generation for $kind",
            )
        }
}

/** Adapts a Swift-implemented [SwiftLocalInferenceEngine] to [LocalInferenceEngine]. */
fun SwiftLocalInferenceEngine.toLocalInferenceEngine(): LocalInferenceEngine =
    object : LocalInferenceEngine {
        override suspend fun probe(): LocalCapacity =
            this@toLocalInferenceEngine.probe()

        override suspend fun generate(prompt: String): Result<String> =
            runCatching { this@toLocalInferenceEngine.generate(prompt) }

        // Forwarded, not left to the interface's default: that would flatten the request
        // here, and the Swift engine would never see its instructions or its budget.
        override suspend fun generate(request: LocalGenerationRequest): Result<String> =
            runCatching { this@toLocalInferenceEngine.generate(request) }

        override suspend fun tokenCount(text: String): Int? =
            try {
                this@toLocalInferenceEngine.tokenCount(text)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }

        override suspend fun generateStructured(kind: EmissionKind, prompt: String): Result<EmissionPayload> =
            runCatching { this@toLocalInferenceEngine.generateStructured(kind, prompt) }
    }
