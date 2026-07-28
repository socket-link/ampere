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
@available(iOS 26.0, *)
final class FoundationModelsLocalInferenceEngine: SwiftLocalInferenceEngine {

    private let session: LanguageModelSession

    override init() {
        self.session = LanguageModelSession()
        super.init()
    }

    override func probe() async throws -> LocalCapacity {
        switch SystemLanguageModel.default.availability {
        case .available:
            return LocalCapacity(
                available: true,
                modelId: Self.modelId,
                maxContextTokens: KotlinInt(int: Self.maxContextTokens),
                providerId: AIProvider_OnDevice.shared.id,
                reason: nil
            )
        case .unavailable(let reason):
            return LocalCapacity(
                available: false,
                modelId: nil,
                maxContextTokens: nil,
                providerId: AIProvider_OnDevice.shared.id,
                reason: Self.describe(reason)
            )
        @unknown default:
            return LocalCapacity(
                available: false,
                modelId: nil,
                maxContextTokens: nil,
                providerId: AIProvider_OnDevice.shared.id,
                reason: "apple_foundation_models_unavailable"
            )
        }
    }

    override func generate(prompt: String) async throws -> String {
        let response = try await session.respond(to: prompt)
        return response.content
    }

    override func generateStructured(kind: EmissionKind, prompt: String) async throws -> EmissionPayload {
        // Prose is a single free-text field — the guided-generation schema
        // path adds nothing over the plain response, so reuse it directly.
        if kind is EmissionKindProse {
            return EmissionPayloadProse(text: try await generate(prompt: prompt), format: .plain)
        }

        let schema = try Self.schema(for: kind)
        let response = try await session.respond(to: prompt, schema: schema)
        return try Self.payload(for: kind, content: response.content)
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

    private static func describe(_ reason: SystemLanguageModel.Availability.UnavailableReason) -> String {
        switch reason {
        case .deviceNotEligible: return "apple_intelligence_device_not_eligible"
        case .appleIntelligenceNotEnabled: return "apple_intelligence_not_enabled"
        case .modelNotReady: return "apple_intelligence_model_not_ready"
        @unknown default: return "apple_intelligence_unavailable"
        }
    }

    /// Provisional pending on-device verification (AMPR-225 recon, §1.1).
    private static let maxContextTokens: Int32 = 4_096
    private static let modelId = "apple-foundation-models-on-device"
}

enum AmpereFoundationModelsError: Swift.Error {
    case unsupportedKind
}
