import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Apple's on-device model on behalf of a JVM host (AMPR-375): the macOS counterpart of
/// `ampere-ios/iosApp/FoundationModelsLocalInferenceEngine.swift`, over a pipe instead of the
/// Kotlin/Native bridge.
///
/// Like the iOS engine, this side reports only what the system says. `inference.probe` is
/// `SystemLanguageModel.default.availability` spelled as a token, plus `contextSize` when, and
/// only when, the model is available. The reason codes Ampere's surfaces translate
/// (`LocalUnavailableReason`), the provider id and the model id are stamped on the JVM side,
/// where Ampere's constants are. No reason string crosses the wire, so nothing here can drift
/// from them.
///
/// Everything degrades to `osTooOld` on a Mac below macOS 26, or in a build whose SDK cannot link
/// FoundationModels.
///
/// ## One session per request
///
/// A `LanguageModelSession` accumulates a transcript, which counts against the context window the
/// host measured the prompt for, and it traps if asked to respond while it is already responding.
/// Each generation therefore gets a fresh session: calls stay independent, the window stays free
/// for the prompt it was sized against, and nothing one request said reaches the next. Counting
/// tokens is asked of the model, never of a session kept to count on.
final class FoundationModelsInference: Sendable {
    /// The availability tokens on the wire, spelled as Socket's helper spells them (SCKT-853). The
    /// host maps them by name and treats one it does not know as `unavailable`, so a case added
    /// here first degrades rather than breaks.
    enum Availability {
        static let available = "available"
        static let deviceNotEligible = "deviceNotEligible"
        static let appleIntelligenceNotEnabled = "appleIntelligenceNotEnabled"
        static let modelNotReady = "modelNotReady"
        static let unavailable = "unavailable"
        static let osTooOld = "osTooOld"
    }

    /// `inference.probe`: never prompts, never loads the model, returns at once.
    func probe() -> [String: Any] {
        #if canImport(FoundationModels)
        if #available(macOS 26.0, *) {
            let model = SystemLanguageModel.default
            switch model.availability {
            case .available:
                // The system's own figure for the running model: 4,096 on macOS 27.0 as measured
                // by AMPR-373. The host adopts it, so a prompt that will not fit is never routed here.
                return ["availability": Availability.available, "contextSize": model.contextSize]
            case .unavailable(let reason):
                return ["availability": Self.token(for: reason)]
            @unknown default:
                return ["availability": Availability.unavailable]
            }
        }
        #endif
        return ["availability": Availability.osTooOld]
    }

    /// `inference.generate`: `{"prompt": …}` in, `{"text": …}` out.
    ///
    /// `instructions`, `maxOutputTokens` and `temperature` are optional, and each goes where the
    /// framework takes it, as the iOS engine passes them (AMPR-462): the instructions to the
    /// session, the cap and the temperature to `GenerationOptions`. The temperature is clamped to
    /// Apple's documented 0 to 1, and a cap that is not positive is sent as no cap. A reply the cap
    /// cuts short comes back as a reply, not a failure: the framework truncates without an error.
    func generate(_ params: JSONValue?) async -> Result<[String: Any], HelperFailure> {
        guard let prompt = params?["prompt"]?.stringValue, !prompt.isEmpty else {
            return .failure(HelperFailure(
                code: Wire.ErrorCode.malformedRequest,
                message: "inference.generate needs a non-empty prompt"
            ))
        }
        #if canImport(FoundationModels)
        if #available(macOS 26.0, *) {
            let options = GenerationOptions(
                temperature: Self.temperature(from: params),
                maximumResponseTokens: Self.maximumResponseTokens(from: params)
            )
            do {
                let session = LanguageModelSession(instructions: params?["instructions"]?.stringValue)
                let response = try await session.respond(to: prompt, options: options)
                return .success(["text": response.content])
            } catch {
                return .failure(HelperFailure(code: Wire.ErrorCode.generationFailed, message: Self.describe(error)))
            }
        }
        #endif
        return .failure(HelperFailure(
            code: Wire.ErrorCode.platformRefused,
            message: "FoundationModels is unavailable on this macOS"
        ))
    }

    /// `inference.count`: the model's own count, so the host sizes a prompt against the window with
    /// the tokenizer that will read it (AMPR-460). `{"tokens": n}` out, for either of two inputs:
    ///
    /// - `{"text": …}` counts one string.
    /// - `{"prompt": …, "instructions": …}` counts what a session for that request starts from: an
    ///   instructions entry, empty when there are none, then the prompt, as the iOS engine counts a
    ///   request (AMPR-462). A string count misses the system turn the framework wraps around every
    ///   call, and the model bills it.
    ///
    /// `tokenCount(for:)` arrived in macOS 26.4. Before that the answer is `{"tokens": null}`,
    /// which the host reads as "cannot count".
    func count(_ params: JSONValue?) async -> Result<[String: Any], HelperFailure> {
        guard let input = Self.countInput(from: params) else {
            return .failure(HelperFailure(
                code: Wire.ErrorCode.malformedRequest,
                message: "inference.count needs a text, or a prompt"
            ))
        }
        #if canImport(FoundationModels)
        if #available(macOS 26.4, *) {
            do {
                let model = SystemLanguageModel.default
                let tokens: Int
                switch input {
                case let .request(instructions, prompt):
                    tokens = try await model.tokenCount(for: Self.transcript(instructions: instructions, prompt: prompt))
                case let .text(text):
                    tokens = try await model.tokenCount(for: text)
                }
                return .success(["tokens": tokens])
            } catch {
                return .failure(HelperFailure(code: Wire.ErrorCode.platformRefused, message: Self.describe(error)))
            }
        }
        #endif
        return .success(["tokens": NSNull()])
    }

    // MARK: - Reading a request
    //
    // What `generate` and `count` make of their params, apart from the model so a test can pin it
    // on a Mac that has none (AMPR-470).

    /// The temperature a generation is sent with: the request's, clamped to Apple's documented 0
    /// to 1, or nil, the framework's default, when it carried none.
    static func temperature(from params: JSONValue?) -> Double? {
        params?["temperature"]?.doubleValue.map { min(max($0, 0), 1) }
    }

    /// The cap a generation is sent with: the request's `maxOutputTokens` when it is a positive
    /// whole number, and no cap otherwise.
    static func maximumResponseTokens(from params: JSONValue?) -> Int? {
        params?["maxOutputTokens"]?.intValue.flatMap { $0 > 0 ? $0 : nil }
    }

    /// What `inference.count` counts.
    enum CountInput: Equatable {
        /// One string, as given.
        case text(String)

        /// The entries a session for this request starts from.
        case request(instructions: String?, prompt: String)
    }

    /// A `prompt` makes it a request, whether or not a `text` came with it. A `text` alone is one
    /// string. Neither is nothing to count.
    static func countInput(from params: JSONValue?) -> CountInput? {
        if let prompt = params?["prompt"]?.stringValue {
            return .request(instructions: params?["instructions"]?.stringValue, prompt: prompt)
        }
        if let text = params?["text"]?.stringValue {
            return .text(text)
        }
        return nil
    }

    /// The framework's own words for a failure where it has them: a guardrail says "May contain
    /// unsafe content", which is what a person needs to read. The type's description otherwise,
    /// which beats "The operation couldn't be completed".
    private static func describe(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? String(describing: error)
    }

    #if canImport(FoundationModels)
    /// The entries a session for a request starts from: its instructions, then its prompt. No
    /// instructions is still an empty instructions entry, because a session without instructions
    /// is billed exactly what one with empty instructions is.
    @available(macOS 26.0, *)
    private static func transcript(instructions: String?, prompt: String) -> [Transcript.Entry] {
        [
            .instructions(Transcript.Instructions(
                segments: [.text(Transcript.TextSegment(content: instructions ?? ""))],
                toolDefinitions: []
            )),
            .prompt(Transcript.Prompt(segments: [.text(Transcript.TextSegment(content: prompt))])),
        ]
    }

    @available(macOS 26.0, *)
    private static func token(for reason: SystemLanguageModel.Availability.UnavailableReason) -> String {
        switch reason {
        case .deviceNotEligible: return Availability.deviceNotEligible
        case .appleIntelligenceNotEnabled: return Availability.appleIntelligenceNotEnabled
        case .modelNotReady: return Availability.modelNotReady
        @unknown default: return Availability.unavailable
        }
    }
    #endif
}
