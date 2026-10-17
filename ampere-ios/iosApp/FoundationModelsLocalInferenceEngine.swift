import Foundation
import FoundationModels
import shared

/// Rung 0 (AMPR-225): binds Apple's on-device Foundation Models to Ampere's
/// `LocalInferenceEngine` contract.
///
/// Subclasses `SwiftLocalInferenceEngine` rather than conforming to
/// `LocalInferenceEngine` directly: Kotlin's `Result<T>` (the Kotlin-side
/// contract's return type) has no Swift-constructible representation across
/// the Kotlin/Native Objective-C export — verified against the generated
/// framework header, which exposes `Result`-returning suspend functions as an
/// opaque `id` with no bridging initializer. `SwiftLocalInferenceEngine`
/// exists precisely to give Swift a plain `async throws` contract instead;
/// `toLocalInferenceEngine()` adapts it back on the Kotlin side.
///
/// Structured generation uses `DynamicGenerationSchema` — built at runtime
/// from the requested `EmissionKind` — rather than a macro-generated
/// `@Generable` Swift type, so there is no compile-time dependency between
/// this file and Ampere's `EmissionPayload` shapes beyond the field names
/// below. Fields are read directly off the resulting `GeneratedContent`
/// (`value(_:forProperty:)`) and used to construct the matching Kotlin
/// `EmissionPayload` case directly — the shape was already constrained at
/// generation time, so this is a typed read, not a parse of free text.
///
/// ## The request's parts, where the framework takes them
///
/// Ampere's client calls `generate(request:)` (AMPR-462). The system message
/// becomes the session's instructions, the user's turn the prompt, and the
/// output budget and temperature `GenerationOptions` — so the budget
/// `AgentLLMService` set is one the model honours, not only the figure the
/// relay reserved when it decided the prompt fits.
///
/// ## One session per call
///
/// `LocalInferenceEngine.generate` is stateless: a request in, text out, no
/// memory of the call before. A `LanguageModelSession` is the opposite — it
/// accumulates a transcript, which counts against the context window, and it
/// traps if asked to respond while it is already responding. So each call gets
/// its own session. That keeps calls independent of each other, keeps the
/// window free for the prompt it was measured against, and makes the engine
/// safe to call from two Arcs at once.
@available(iOS 26.0, *)
final class FoundationModelsLocalInferenceEngine: SwiftLocalInferenceEngine {

    override func probe() async throws -> LocalCapacity {
        let model = SystemLanguageModel.default
        switch model.availability {
        case .available:
            return LocalCapacity(
                available: true,
                modelId: Self.modelId,
                // The system's own figure for the running model (`contextSize` is iOS 26
                // API, back-deployed before 26.4). 4,096 on iOS 26.4.1, iOS 27.1 and
                // macOS 27.0 as measured by AMPR-373. The relay reads this to keep a
                // prompt that will not fit from being routed here.
                maxContextTokens: KotlinInt(int: Int32(clamping: model.contextSize)),
                providerId: AIProvider_OnDevice.shared.id,
                reason: nil
            )
        case .unavailable(let reason):
            return Self.unavailable(reason: Self.describe(reason))
        @unknown default:
            return Self.unavailable(reason: LocalUnavailableReason.shared.UNAVAILABLE)
        }
    }

    /// The bare form, for a caller with a prompt alone: no instructions, the framework's own options.
    override func generate(prompt: String) async throws -> String {
        let response = try await LanguageModelSession().respond(to: prompt)
        return response.content
    }

    /// A reply that reaches `maximumResponseTokens` is cut short by the framework without an
    /// error, so it comes back as an answer — mid-sentence if need be — and never as a failure
    /// to re-send. Asked to count from 1 to 100 under a 16-token cap, the model stopped at 6.
    override func generate(request: LocalGenerationRequest) async throws -> String {
        try await Self.respond(to: request).content
    }

    /// One session for one request, as `generate(request:)` runs it. Kept apart so a test can
    /// read the response's own usage report, which `generate` does not return.
    static func respond(to request: LocalGenerationRequest) async throws -> LanguageModelSession.Response<String> {
        try await LanguageModelSession(instructions: request.instructions)
            .respond(to: request.prompt, options: options(for: request))
    }

    /// Apple documents `temperature` as 0 to 1 inclusive and `maximumResponseTokens` as positive.
    /// Ampere's temperature runs on the same 0-to-1 scale, so one outside it is clamped to the
    /// nearer end, and a cap that is not positive is sent as no cap. The live model took 1.5, -0.5
    /// and a cap of 0 without an error (AMPR-462); this keeps a request inside what is documented
    /// rather than inside what happens to work today.
    static func options(for request: LocalGenerationRequest) -> GenerationOptions {
        GenerationOptions(
            temperature: request.temperature.map { min(max($0.doubleValue, 0), 1) },
            maximumResponseTokens: request.maxOutputTokens.map { $0.intValue }.flatMap { $0 > 0 ? $0 : nil }
        )
    }

    /// The model's own count of `text` (AMPR-460), so the relay sizes a prompt against the
    /// 4,096-token window with the tokenizer that will read it rather than with four
    /// characters per token — which runs 1.55× low on a log full of timestamps (AMPR-372).
    /// `tokenCount(for:)` arrived in iOS 26.4; before that the engine cannot count and says
    /// so with `nil`, leaving the caller on the estimate. Counted on the model itself, not on
    /// a session: nothing is kept between calls. A throw here is adapted on the Kotlin side
    /// to "cannot count" and never fails the call.
    override func tokenCount(text: String) async throws -> KotlinInt? {
        guard #available(iOS 26.4, *) else { return nil }
        let count = try await SystemLanguageModel.default.tokenCount(for: text)
        return KotlinInt(int: Int32(clamping: count))
    }

    /// What `request` costs the model as input (AMPR-462), which is what the relay's window fit
    /// asks: the transcript a session for it starts from, counted by the model. A string count
    /// misses the system turn the framework wraps around every call — about 50 tokens — and the
    /// model's usage report bills it: 137 input tokens for a one-line question under the
    /// on-device assistant's instructions, where the flattened string counts 87. Counted this
    /// way the two agreed exactly on every prompt measured, with instructions and without.
    override func tokenCount(request: LocalGenerationRequest) async throws -> KotlinInt? {
        guard #available(iOS 26.4, *) else { return nil }
        let count = try await SystemLanguageModel.default.tokenCount(for: Self.transcript(for: request))
        return KotlinInt(int: Int32(clamping: count))
    }

    /// The entries a session for `request` starts from: its instructions, then its prompt. A
    /// request with no instructions still gets an empty instructions entry, because a session
    /// without instructions is billed exactly what one with empty instructions is.
    static func transcript(for request: LocalGenerationRequest) -> [Transcript.Entry] {
        [
            .instructions(Transcript.Instructions(
                segments: [.text(Transcript.TextSegment(content: request.instructions ?? ""))],
                toolDefinitions: []
            )),
            .prompt(Transcript.Prompt(segments: [.text(Transcript.TextSegment(content: request.prompt))])),
        ]
    }

    override func generateStructured(kind: EmissionKind, prompt: String) async throws -> EmissionPayload {
        // Prose is a single free-text field — the guided-generation schema
        // path adds nothing over the plain response, so reuse it directly.
        if kind is EmissionKindProse {
            return EmissionPayloadProse(text: try await generate(prompt: prompt), format: .plain)
        }

        let schema = try Self.schema(for: kind)
        let response = try await LanguageModelSession().respond(to: prompt, schema: schema)
        return try Self.payload(for: kind, content: response.content)
    }

    private static func unavailable(reason: String) -> LocalCapacity {
        LocalCapacity(
            available: false,
            modelId: nil,
            maxContextTokens: nil,
            providerId: AIProvider_OnDevice.shared.id,
            reason: reason
        )
    }

    // MARK: - Schema construction (AMPR-225: DynamicGenerationSchema, no @Generable macro needed)

    private static func schema(for kind: EmissionKind) throws -> GenerationSchema {
        let root: DynamicGenerationSchema
        switch kind {
        case is EmissionKindDecision:
            root = DynamicGenerationSchema(
                name: "Decision",
                properties: [
                    stringProperty(name: "prompt"),
                    stringProperty(name: "context", isOptional: true),
                ]
            )
        case is EmissionKindConfirmation:
            root = DynamicGenerationSchema(
                name: "Confirmation",
                properties: [
                    stringProperty(name: "action"),
                    stringProperty(name: "preview", isOptional: true),
                    .init(
                        name: "dangerLevel",
                        schema: DynamicGenerationSchema(
                            name: "DangerLevel",
                            anyOf: ["LOW", "MEDIUM", "HIGH"]
                        )
                    ),
                ]
            )
        case is EmissionKindSensor:
            root = DynamicGenerationSchema(
                name: "Sensor",
                properties: [
                    stringProperty(name: "label"),
                    stringProperty(name: "value"),
                    stringProperty(name: "unit", isOptional: true),
                    stringProperty(name: "refreshUri", isOptional: true),
                ]
            )
        default:
            throw AmpereFoundationModelsError.unsupportedKind
        }
        return try GenerationSchema(root: root, dependencies: [])
    }

    private static func stringProperty(name: String, isOptional: Bool = false) -> DynamicGenerationSchema.Property {
        .init(name: name, schema: DynamicGenerationSchema(type: String.self), isOptional: isOptional)
    }

    // MARK: - Reading the constrained result back into a typed EmissionPayload

    private static func payload(for kind: EmissionKind, content: GeneratedContent) throws -> EmissionPayload {
        switch kind {
        case is EmissionKindDecision:
            return EmissionPayloadDecision(
                prompt: try content.value(String.self, forProperty: "prompt"),
                context: try content.value(String?.self, forProperty: "context")
            )
        case is EmissionKindConfirmation:
            let dangerLevelName = try content.value(String.self, forProperty: "dangerLevel")
            return EmissionPayloadConfirmation(
                action: try content.value(String.self, forProperty: "action"),
                preview: try content.value(String?.self, forProperty: "preview"),
                dangerLevel: dangerLevel(named: dangerLevelName)
            )
        case is EmissionKindSensor:
            return EmissionPayloadSensor(
                label: try content.value(String.self, forProperty: "label"),
                value: try content.value(String.self, forProperty: "value"),
                unit: try content.value(String?.self, forProperty: "unit"),
                refreshUri: try content.value(String?.self, forProperty: "refreshUri")
            )
        default:
            throw AmpereFoundationModelsError.unsupportedKind
        }
    }

    private static func dangerLevel(named name: String) -> DangerLevel {
        switch name.uppercased() {
        case "HIGH": return .high
        case "MEDIUM": return .medium
        default: return .low
        }
    }

    /// Reason codes are declared once, in Kotlin's `LocalUnavailableReason`, so the
    /// engine that reports one and the surface that words it cannot drift apart.
    private static func describe(_ reason: SystemLanguageModel.Availability.UnavailableReason) -> String {
        switch reason {
        case .deviceNotEligible: return LocalUnavailableReason.shared.DEVICE_NOT_ELIGIBLE
        case .appleIntelligenceNotEnabled: return LocalUnavailableReason.shared.NOT_ENABLED
        case .modelNotReady: return LocalUnavailableReason.shared.MODEL_NOT_READY
        @unknown default: return LocalUnavailableReason.shared.UNAVAILABLE
        }
    }

    /// Must match `AIModel_OnDevice.AppleFoundationModels.name`: the relay selects the
    /// on-device route by that name, and telemetry reports it under the same one.
    private static let modelId = AIModel_OnDevice.AppleFoundationModels.shared.name
}

enum AmpereFoundationModelsError: Swift.Error {
    case unsupportedKind
}
