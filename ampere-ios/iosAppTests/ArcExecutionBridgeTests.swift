import XCTest
import shared

/// The Arc execution bridge, exercised across the Objective-C boundary (AMPR-243).
///
/// The Kotlin-side behaviour is pinned by `ArcSessionTest` (JVM) and `EmissionStreamTest`
/// (JVM + iOS Native). What can only be proved *here* is that the export survives: that Swift
/// can build a session without touching a `CoroutineScope`, drive `async` methods off the main
/// actor, consume the progress stream as an `AsyncSequence`, and pattern-match the Emission
/// hierarchy after it has been flattened into Objective-C classes.
final class ArcExecutionBridgeTests: XCTestCase {

    private var projectDir: URL!
    private var session: ArcSession?

    override func setUpWithError() throws {
        projectDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("arc-bridge-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: projectDir, withIntermediateDirectories: true)

        // ChargePhase reads its project context from these two files.
        try "# BridgeProject\n\nA test project for the Arc bridge.\n"
            .write(to: projectDir.appendingPathComponent("README.md"), atomically: true, encoding: .utf8)
        try """
        # AGENTS

        ## Dependencies
        - Kotlin

        ## Conventions
        - Use suspend functions

        ## Architecture
        - Clean architecture
        """.write(to: projectDir.appendingPathComponent("AGENTS.md"), atomically: true, encoding: .utf8)
    }

    override func tearDownWithError() throws {
        session?.close()
        session = nil
        try? FileManager.default.removeItem(at: projectDir)
    }

    private func makeSession(maxFlowTicks: Int32) -> ArcSession {
        // Every argument is something Swift can build. That is the point of the factory:
        // kotlinx.coroutines is not exported, so `CoroutineScope` has no Swift constructor and
        // the three-argument `ArcSession.init` is unreachable from here.
        let session = ArcSession.companion.create(
            arcConfig: ArcRegistry.shared.getDefault(),
            projectDirPath: projectDir.path,
            maxFlowTicks: maxFlowTicks
        )
        self.session = session
        return session
    }

    private func progressEvent(runId: String, text: String) -> EmissionEventBaseProduced {
        EmissionEventBaseProduced(
            eventId: "evt-\(text)",
            timestamp: Kotlinx_datetimeInstant.Companion.shared.fromEpochMilliseconds(
                epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000)
            ),
            // Nested, not flattened: EventSource is a sealed *class*, so the export keeps the
            // nesting. The sealed *interfaces* below (EmissionKind, EmissionPayload) do not.
            eventSource: EventSource.Agent(agentId: "code"),
            urgency: Urgency.medium,
            emission: Emission(
                id: "emission-\(text)",
                kind: EmissionKindProse(),
                payload: EmissionPayloadProse(text: text, format: .plain),
                affordances: [],
                confidence: nil,
                provenance: EmissionProvenance(
                    runId: runId,
                    workflowId: nil,
                    sourceEventId: nil,
                    toolInvocationId: nil,
                    plugId: nil,
                    modelId: nil,
                    inputDigest: "digest-\(text)"
                ),
                dedupKey: nil,
                producedAt: Kotlinx_datetimeInstant.Companion.shared.fromEpochMilliseconds(
                    epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000)
                ),
                surfaces: [],
                fallbackUrl: nil
            )
        )
    }

    /// The launch scenario end to end: start an Arc, watch progress arrive on the `AsyncStream`,
    /// then cancel mid-flight and get a settled `Cancelled` back.
    ///
    /// This is a `ProgressReportingIntent` plus a `CancellableIntent`, minus the App Intents.
    func testObserveProgressThenCancelMidFlight() async throws {
        let session = makeSession(maxFlowTicks: Int32.max)
        let handle = session.start(userGoal: "Implement a very long running goal")

        let expected = 3
        let received = XCTestExpectation(description: "\(expected) progress Emissions observed")
        received.expectedFulfillmentCount = expected

        var texts: [String] = []
        let collected = _Concurrency.Task { () -> [String] in
            for await emission in handle.emissions {
                if let prose = emission.payload as? EmissionPayloadProse {
                    texts.append(prose.text)
                    received.fulfill()
                }
            }
            return texts
        }

        for index in 0..<expected {
            try await session.bus.publish(event: progressEvent(runId: handle.runId, text: "progress-\(index)"))
        }
        await fulfillment(of: [received], timeout: 30)

        let outcome = try await handle.cancel()
        XCTAssertTrue(outcome is ArcOutcomeCancelled, "Expected Cancelled, got \(outcome)")

        // Cancelling ends the run, which ends the stream, which lets this task return.
        let seen = await collected.value
        XCTAssertEqual(
            Set(seen),
            Set((0..<expected).map { "progress-\($0)" }),
            "Every published Emission must reach the Swift consumer"
        )
    }

    /// A run that is allowed to finish reports `Completed`, and its progress stream ends with it.
    func testRunCompletesAndFinishesItsEmissionStream() async throws {
        let session = makeSession(maxFlowTicks: 1)
        let handle = session.start(userGoal: "Add a health check endpoint")

        // Draining the stream must terminate. If `onFinished` never crossed the boundary this
        // would hang until the test timeout rather than fail — which is the bug worth catching,
        // because a Live Activity would hang the same way.
        let drained = _Concurrency.Task { () -> Int in
            var count = 0
            for await _ in handle.emissions { count += 1 }
            return count
        }

        let outcome = try await handle.outcome()
        XCTAssertTrue(outcome is ArcOutcomeCompleted, "Expected Completed, got \(outcome)")
        XCTAssertEqual(outcome.runId, handle.runId)

        _ = await drained.value
        XCTAssertFalse(handle.isActive)
    }

    /// The stop button: `cancel` halts a run in flight and settles before it returns.
    func testCancelHaltsARunInFlight() async throws {
        let session = makeSession(maxFlowTicks: Int32.max)
        let handle = session.start(userGoal: "Implement a very long running goal")

        let outcome = try await handle.cancel()

        XCTAssertTrue(outcome is ArcOutcomeCancelled, "Expected Cancelled, got \(outcome)")
        XCTAssertEqual(outcome.runId, handle.runId)
        XCTAssertFalse(handle.isActive)

        // Idempotent, so a stop button can be pressed twice without special-casing.
        let again = try await handle.cancel()
        XCTAssertTrue(again is ArcOutcomeCancelled)
    }

    /// Phase-2 empirical check 1: an App Intent's `perform()` is not guaranteed to run on the
    /// main actor, so the exported suspend functions have to work off it.
    func testSuspendFunctionsWorkOffTheMainActor() async throws {
        let session = makeSession(maxFlowTicks: Int32.max)
        let handle = session.start(userGoal: "Implement a very long running goal")

        let outcome = try await _Concurrency.Task.detached(priority: .background) { () -> ArcOutcome in
            XCTAssertFalse(Thread.isMainThread, "Precondition: this must not be the main thread")
            return try await handle.cancel()
        }.value

        XCTAssertTrue(outcome is ArcOutcomeCancelled, "Expected Cancelled, got \(outcome)")
    }

    /// Phase-2 empirical check 2: `EmissionKind` and `EmissionPayload` are Kotlin sealed
    /// interfaces, which the Objective-C export flattens into unrelated classes. Exhaustive
    /// matching is gone; this pins that non-exhaustive matching still works, which is all a
    /// renderer needs.
    func testEmissionHierarchiesArePatternMatchable() throws {
        let payload: EmissionPayload = EmissionPayloadProse(text: "hello", format: .plain)
        let kind: EmissionKind = EmissionKindProse()

        switch payload {
        case let prose as EmissionPayloadProse:
            XCTAssertEqual(prose.text, "hello")
            XCTAssertEqual(prose.format, ProseFormat.plain)
        case is EmissionPayloadDecision, is EmissionPayloadConfirmation, is EmissionPayloadSensor:
            XCTFail("Prose payload matched the wrong case")
        default:
            XCTFail("Prose payload matched no case at all")
        }

        XCTAssertTrue(kind is EmissionKindProse)
        XCTAssertFalse(kind is EmissionKindSensor)
    }

    /// Cancelling the consuming `Task` releases the bus subscription rather than leaking it.
    func testCancellingTheConsumerReleasesTheObservation() async throws {
        let session = makeSession(maxFlowTicks: Int32.max)
        let handle = session.start(userGoal: "Implement a very long running goal")

        let consumer = _Concurrency.Task {
            for await _ in handle.emissions { /* drained until cancelled */ }
        }
        consumer.cancel()
        _ = await consumer.result

        // The run is untouched by the consumer going away.
        XCTAssertTrue(handle.isActive)

        let outcome = try await handle.cancel()
        XCTAssertTrue(outcome is ArcOutcomeCancelled)
    }
}
