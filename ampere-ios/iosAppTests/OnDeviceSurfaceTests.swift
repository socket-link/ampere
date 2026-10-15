import FoundationModels
import UIKit
import XCTest
import shared

/// The app's own surface asking the real model (AMPR-373): a prompt typed into the ask panel
/// and sent with its button, with the indicator read back through the same accessibility tree
/// a screen reader would use — "On-device ready" before, "Running on this device" during, and
/// "Answered on this device" after.
///
/// `OnDeviceGenerationTests` drives a session the test built. This drives the session the app
/// built in `mainViewController`, through the Compose surface, in the test host's process.
/// There is no UI-test target, so it reaches the surface by accessibility: Compose activates
/// its accessibility tree on the first `accessibilityElements` query, every clickable element
/// answers `accessibilityActivate`, and a focused text field makes Compose's input view the
/// first responder, which takes `insertText`.
///
/// Skips where the model cannot generate (`OnDeviceModelGate`), like its sibling. Lines prefixed `AMPR373|surface|`
/// carry the timings, and a host watching the simulator while this runs can screenshot the
/// running state.
@available(iOS 26.0, *)
final class OnDeviceSurfaceTests: XCTestCase {

    override func setUp() async throws {
        try await OnDeviceModelGate.requireReady()
    }

    @MainActor
    func testAskingThroughTheSurfaceLightsTheIndicatorAndAnswersOnTheDevice() async throws {
        try await settle(seconds: 2)
        let window = try XCTUnwrap(UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first)

        let readyAtStart = try await waitForLabel(containing: "On-device ready", in: window, seconds: 20)
        XCTAssertNotNil(readyAtStart, "the indicator never reported ready: \(labels(in: window))")

        let field = try XCTUnwrap(element(in: window, labelContaining: "Ask the on-device model"),
                                  "no ask field: \(labels(in: window))")
        XCTAssertTrue(field.accessibilityActivate(), "the ask field did not take focus")
        try await settle(seconds: 0.5)

        let input = try XCTUnwrap(firstResponder(in: window) as? UIKeyInput,
                                  "no key-input first responder after focusing the field")
        // Long enough for the model to take a few seconds, so the running state can be seen.
        input.insertText(Self.prompt)
        try await settle(seconds: 0.5)

        let ask = try XCTUnwrap(element(in: window, labelContaining: "Ask"), "no ask button: \(labels(in: window))")
        let pressed = Date()
        XCTAssertTrue(ask.accessibilityActivate(), "the ask button did not activate")

        let running = try await waitForLabel(containing: "Running on this device", in: window, seconds: 15)
        let sawRunningAfterMs = running.map { Int($0.timeIntervalSince(pressed) * 1000) }

        let answered = try await waitForLabel(containing: "Answered on this device", in: window, seconds: 60)
        let answeredAfterMs = answered.map { Int($0.timeIntervalSince(pressed) * 1000) }
        let ready = try await waitForLabel(containing: "On-device ready", in: window, seconds: 10)

        let line = "sawRunningAfterMs=\(sawRunningAfterMs.map(String.init) ?? "never") | answeredAfterMs=\(answeredAfterMs.map(String.init) ?? "never") | readyAgain=\(ready != nil) | labels=\(labels(in: window).joined(separator: " / "))"
        print("AMPR373|surface|\(line)")
        let attachment = XCTAttachment(string: line)
        attachment.name = "AMPR-373 surface"
        attachment.lifetime = .keepAlways
        add(attachment)

        XCTAssertNotNil(running, "the indicator never showed the running state")
        XCTAssertNotNil(answered, "the outcome card never said the answer came from the device")
        XCTAssertNotNil(ready, "the indicator did not return to ready")
    }

    private static let prompt =
        String(repeating: "The quick brown fox jumps over the lazy dog. ", count: 120) +
        "How many sentences are above? Reply with a number only."

    // MARK: - Accessibility walking

    private func settle(seconds: Double) async throws {
        try await _Concurrency.Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    @MainActor
    private func waitForLabel(containing text: String, in window: UIWindow, seconds: Double) async throws -> Date? {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if element(in: window, labelContaining: text) != nil { return Date() }
            try await settle(seconds: 0.1)
        }
        return nil
    }

    @MainActor
    private func element(in window: UIWindow, labelContaining text: String) -> NSObject? {
        var found: NSObject?
        walk(window) { element in
            if found == nil, let label = element.accessibilityLabel, label.contains(text) { found = element }
        }
        return found
    }

    @MainActor
    private func labels(in window: UIWindow) -> [String] {
        var out: [String] = []
        walk(window) { element in
            if let label = element.accessibilityLabel, !label.isEmpty { out.append(label) }
        }
        return out
    }

    /// Visits every accessibility element under [root]: views by subview, containers by their
    /// `accessibilityElements`. Querying a Compose root's elements is what activates its tree.
    @MainActor
    private func walk(_ root: NSObject, visit: (NSObject) -> Void) {
        visit(root)
        if let elements = root.accessibilityElements as? [NSObject] {
            elements.forEach { walk($0, visit: visit) }
        }
        if let view = root as? UIView {
            view.subviews.forEach { walk($0, visit: visit) }
        }
    }

    @MainActor
    private func firstResponder(in view: UIView) -> UIView? {
        if view.isFirstResponder { return view }
        for subview in view.subviews {
            if let responder = firstResponder(in: subview) { return responder }
        }
        return nil
    }
}
