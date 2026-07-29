import Foundation
import shared

/// Swift's view of a running Arc.
///
/// This extension is the only hand-written part of the KMP↔Swift Arc bridge. Everything else —
/// `start`, `await`, `observe`, `cancel` — crosses the boundary as a plain Objective-C export,
/// which Swift re-imports as `async`. The one thing the export cannot do is turn a Kotlin
/// callback into an `AsyncSequence`, so that is what lives here.
///
/// Deliberately shaped like what SKIE would emit. If the project adopts SKIE later, this file
/// goes away and no call site changes.
///
/// ```swift
/// let session = ArcSession.companion.create(
///     arcConfig: ArcRegistry.shared.getDefault(),
///     projectDirPath: projectPath,
///     maxFlowTicks: 100
/// )
/// let handle = session.start(userGoal: goal)
///
/// Task { for await emission in handle.emissions { await reporter.update(emission) } }
/// let outcome = try await handle.outcome()
/// ```
extension ArcRunHandle {

    /// This run's progress Emissions, in the order the bus delivered them.
    ///
    /// The stream finishes on its own when the run reaches a terminal outcome, so a `for await`
    /// loop ends without being told to — which is what keeps a `ProgressReportingIntent` from
    /// hanging on a run that is already over. Breaking out of the loop, or cancelling the
    /// enclosing `Task`, releases the underlying bus subscription.
    ///
    /// Buffering is `.unbounded` here because the Kotlin side already applies the overflow
    /// policy: a collector that falls behind loses the *oldest* Emissions and is told how many.
    /// Adding a second bound on this side would drop the newest instead.
    var emissions: AsyncStream<Emission> {
        AsyncStream(Emission.self, bufferingPolicy: .unbounded) { continuation in
            let token = self.observe(
                onEmission: { emission in continuation.yield(emission) },
                onFinished: { continuation.finish() }
            )
            continuation.onTermination = { _ in token.cancel() }
        }
    }

    /// The run's terminal outcome — the same call as `await()`, under a name that does not read
    /// as a keyword at the call site.
    func outcome() async throws -> ArcOutcome {
        try await self.`await`()
    }
}
