import XCTest
import shared

/// A roster authored from Swift (AMPR-413).
///
/// The roster types are pinned on the JVM by `RosterConfigTest`, `SeatToolTest` and
/// `SeatRoutingTest`. What can only be proved *here* is that a consumer can author one
/// from the platform it renders on: `RoleId` is a Kotlin inline value class, which the
/// Objective-C export erases to `Any?` and whose companion it does not export, so the
/// `RoleConfig` and `RosterConfig` constructors are unreachable from Swift and the
/// plain-id factories are the whole authoring surface.
///
/// Nothing here asks a factory to refuse a bad roster: the checks are `require`, and an
/// uncaught Kotlin exception terminates the process rather than arriving as a Swift
/// error. Refusal is `RosterConfigTest`'s subject.
final class RosterAuthoringTests: XCTestCase {

    private let bigModel = "the-big-one"

    /// A seat with a model and an effort to run at, and one tool it shares with the Inspector.
    private func makeScout() -> RoleConfig {
        RoleConfig.companion.of(
            id: "scout",
            title: "Scout",
            instructions: PromptRef(id: "consumer.scout", version: 1),
            tools: ["web_search", "web_fetch"],
            reviews: [],
            execution: ExecutionAssignment(model: bigModel, effort: EffortLevel.high)
        )
    }

    /// A seat that reviews the Scout, shares its search tool, and declares no execution.
    private func makeInspector() -> RoleConfig {
        RoleConfig.companion.of(
            id: "inspector",
            title: "Inspector",
            instructions: PromptRef(id: "consumer.inspector", version: 1),
            tools: ["web_search"],
            reviews: ["scout"],
            execution: nil
        )
    }

    private func makeRoster() -> RosterConfig {
        RosterConfig.companion.of(
            host: "scout",
            roles: [makeScout(), makeInspector()],
            verifier: "inspector"
        )
    }

    /// Every parameter of a roster is a type Swift can build, and the roster reads back.
    func testAConsumerCanAuthorARosterFromSwift() {
        let roster = makeRoster()

        XCTAssertEqual(roster.all().count, 2)
        XCTAssertEqual(roster.all().first?.title, "Scout")
        XCTAssertNotNil(roster.host)
        XCTAssertNotNil(roster.verifier, "the reviewing seat is a value, not an override")
        XCTAssertNil(roster.all().last?.execution, "a seat may decline to say how it runs")
    }

    /// Two seats declaring one tool, and the roster saying which of them runs a given id.
    func testTwoSeatsShareOneToolAndTheRosterSaysWhichSeatRunsIt() {
        let roster = makeRoster()

        let byScout = SeatTool.companion.of(seat: "scout", tool: "web_search")
        let byInspector = SeatTool.companion.of(seat: "inspector", tool: "web_search")
        XCTAssertEqual(byScout.id, "scout/web_search")
        XCTAssertEqual(byInspector.id, "inspector/web_search")

        XCTAssertEqual(roster.seatRunning(qualifiedToolId: byScout.id)?.title, "Scout")
        XCTAssertEqual(roster.seatRunning(qualifiedToolId: byInspector.id)?.title, "Inspector")
        XCTAssertNil(roster.seatRunning(qualifiedToolId: "inspector/web_fetch"), "never declared there")
        XCTAssertNil(roster.seatRunning(qualifiedToolId: "web_search"), "a bare id names no seat")
    }

    /// A seat's execution assignment reaches a routing context as tags, and a seat
    /// without one leaves the context alone.
    func testASeatsExecutionAssignmentTagsTheCallsMadeAsThatSeat() {
        let call = RoutingContext(
            phase: nil,
            agentId: nil,
            agentRole: nil,
            workflowId: nil,
            preferredReasoning: nil,
            preferredSpeed: nil,
            tags: [],
            requirements: nil,
            localCapacity: nil
        )

        let asScout = call.withSeat(seat: makeScout())
        XCTAssertTrue(asScout.tags.contains(ExecutionTags.shared.model(modelId: bigModel)))
        XCTAssertTrue(asScout.tags.contains(ExecutionTags.shared.effort(effort: EffortLevel.high)))

        let asInspector = call.withSeat(seat: makeInspector())
        XCTAssertTrue(asInspector.tags.isEmpty, "a seat that declares nothing tags nothing")
    }
}
