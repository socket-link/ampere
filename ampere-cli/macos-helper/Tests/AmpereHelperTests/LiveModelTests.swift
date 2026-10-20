import Foundation
import XCTest
@testable import AmpereHelper

/// The helper against the live model (AMPR-470): what the 2026-10-17 hand check showed on macOS
/// 27.0, re-run whenever the suite is.
///
/// Every case skips unless `OnDeviceModelGate` got one word out of the model, because an
/// unavailable model is a fact about the machine, not about the helper. Requests go through
/// `AmpereHelper.handle(line:inference:)`, so each case covers the wire and the framework call
/// together. Assertions stay on what the framework guarantees, never on what the model chooses to
/// say: a reply is text and not an error, and a count is a number with the right order.
final class LiveModelTests: XCTestCase {
    override func setUp() async throws {
        try await OnDeviceModelGate.requireReady()
    }

    func testInferenceProbeReportsAvailableWithAContextSize() async throws {
        let reply = try await Exchange.send(Exchange.request(op: Wire.Op.inferenceProbe))

        XCTAssertEqual(reply.ok, true, "\(String(describing: reply.error))")
        XCTAssertEqual(reply.result?["availability"] as? String, FoundationModelsInference.Availability.available)
        let contextSize = try XCTUnwrap(reply.result?["contextSize"] as? Int, "available, so a context size")
        XCTAssertGreaterThan(contextSize, 0)
    }

    /// A request is counted as the entries a session starts from, which carry a system turn a
    /// string count misses: 73 tokens against 23 on 2026-10-17. Only the order is asserted.
    func testARequestCountsMoreThanItsFlattenedString() async throws {
        let instructions = "You are a terse assistant. Answer in one sentence."
        let prompt = "What colour is the sky on a clear day?"
        let flattened = "System: \(instructions)\n\nUser: \(prompt)"

        let request = try await Exchange.send(Exchange.request(
            id: "request",
            op: Wire.Op.inferenceCount,
            params: json(["instructions": instructions, "prompt": prompt])
        ))
        let string = try await Exchange.send(Exchange.request(
            id: "string",
            op: Wire.Op.inferenceCount,
            params: json(["text": flattened])
        ))

        XCTAssertEqual(request.ok, true, "\(String(describing: request.error))")
        XCTAssertEqual(string.ok, true, "\(String(describing: string.error))")
        guard let requestTokens = request.result?["tokens"] as? Int,
              let stringTokens = string.result?["tokens"] as? Int
        else {
            // `{"tokens": null}` is the helper's answer below macOS 26.4, where the framework has no
            // count. That is the documented reply there, not a failure.
            throw XCTSkip("tokenCount(for:) needs macOS 26.4; the helper answered \(String(describing: request.result))")
        }
        XCTAssertGreaterThan(stringTokens, 0)
        XCTAssertGreaterThan(requestTokens, stringTokens, "the request's count must include the session's own turn")
    }

    /// The framework truncates at the cap without an error, so a capped call answers.
    func testACappedGenerationReturnsTextWithoutAnError() async throws {
        let reply = try await Exchange.send(Exchange.request(
            op: Wire.Op.inferenceGenerate,
            params: json(["prompt": "Count from 1 to 100, separated by commas.", "maxOutputTokens": 16])
        ))

        XCTAssertEqual(reply.ok, true, "\(String(describing: reply.error))")
        let text = try XCTUnwrap(reply.result?["text"] as? String)
        XCTAssertFalse(text.isEmpty)
        // The whole count is about 290 characters, and 16 tokens of digits and commas are far
        // fewer. On 2026-10-17 the model stopped at 6. A reply this long means the cap never arrived.
        XCTAssertLessThan(text.count, 200, "the cap was not applied: \(text)")
    }

    func testAGenerationWithInstructionsReturnsText() async throws {
        let reply = try await Exchange.send(Exchange.request(
            op: Wire.Op.inferenceGenerate,
            params: json([
                "instructions": "You are a terse assistant. Answer in one short sentence.",
                "prompt": "Name a colour.",
            ])
        ))

        XCTAssertEqual(reply.ok, true, "\(String(describing: reply.error))")
        let text = try XCTUnwrap(reply.result?["text"] as? String)
        XCTAssertFalse(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    // MARK: - Params

    private func json(_ fields: [String: Any]) -> String {
        let data = try! JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }
}
