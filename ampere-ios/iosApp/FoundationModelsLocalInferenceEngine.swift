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
/// ## One session per call
///
/// `LocalInferenceEngine.generate` is stateless: a prompt in, text out, no
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
                // The system's own figure: 4,096 before iOS 27, the running model's
                // real window from iOS 27 on (AMPR-327). The relay reads this to keep
                // a prompt that will not fit from being routed here.
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

    override func generate(prompt: String) async throws -> String {
        let response = try await LanguageModelSession().respond(to: prompt)
        return response.content
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
