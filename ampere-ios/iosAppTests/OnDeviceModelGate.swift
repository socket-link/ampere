import FoundationModels
import XCTest

/// Whether the on-device model can actually generate here, decided once per test process.
///
/// `SystemLanguageModel.availability` is not enough: GitHub's hosted macOS runners report
/// `.available` with a `contextSize`, then fail every generation with
/// `GenerationError error -1` because the model assets were never downloaded
/// ("no underlying assets … com.apple.MobileAsset.UAF.FM"). So the gate asks the model for one
/// word. Only a system that answers runs the on-device tests; anywhere else they skip, and the
/// skip names the reason so a log reader can tell "off" from "broken".
@available(iOS 26.0, *)
enum OnDeviceModelGate {

    enum Verdict {
        case ready
        case unavailable(String)
    }

    private static var cached: Verdict?

    static func verdict() async -> Verdict {
        if let cached { return cached }
        let verdict: Verdict
        switch SystemLanguageModel.default.availability {
        case .available:
            do {
                _ = try await LanguageModelSession().respond(to: "Reply with the single word OK.")
                verdict = .ready
            } catch {
                verdict = .unavailable("the model reports available but cannot generate: \(error)")
            }
        case .unavailable(let reason):
            verdict = .unavailable("Apple Intelligence is not available on this system: \(reason)")
        @unknown default:
            verdict = .unavailable("Apple Intelligence availability is unknown on this system")
        }
        cached = verdict
        return verdict
    }

    /// Throws `XCTSkip` unless the model generated once.
    static func requireReady() async throws {
        if case .unavailable(let reason) = await verdict() {
            throw XCTSkip(reason)
        }
    }
}
