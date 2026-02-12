package link.socket.ampere.cli.animation.demo

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A mock event for demo playback.
 *
 * @property time Time offset from start when this event should trigger
 * @property action The event action to execute
 */
data class MockEvent(
    val time: Duration,
    val action: DemoAction
)

/**
 * Actions that can be executed during demo playback.
 */
sealed class DemoAction {
    /** Spawn an agent with visual animation */
    data class AgentSpawned(
        val agentId: String,
        val displayName: String,
        val role: String = "reasoning"
    ) : DemoAction()

    /** Update agent status text */
    data class AgentStatusChanged(
        val agentId: String,
        val status: String
    ) : DemoAction()

    /** Set agent to active state */
    data class AgentActivated(val agentId: String) : DemoAction()

    /** Set agent to idle state */
    data class AgentIdle(val agentId: String) : DemoAction()

    /** Set agent to complete state */
    data class AgentComplete(val agentId: String) : DemoAction()

    /** Create a task assignment */
    data class TaskCreated(
        val taskId: String,
        val description: String,
        val assignedTo: String
    ) : DemoAction()

    /** Complete a task */
    data class TaskCompleted(val taskId: String) : DemoAction()

    /** Start a handoff between agents */
    data class HandoffStarted(
        val fromAgentId: String,
        val toAgentId: String
    ) : DemoAction()

    /** Complete a handoff */
    data class HandoffCompleted(
        val fromAgentId: String,
        val toAgentId: String
    ) : DemoAction()

    /** Stream output text */
    data class OutputChunk(val text: String) : DemoAction()

    /** Start logo crystallization */
    object LogoStart : DemoAction()

    /** Trigger substrate pulse effect */
    data class SubstratePulse(val x: Float, val y: Float) : DemoAction()

    /** Display stats summary */
    data class ShowStats(val summary: String) : DemoAction()

    /** Demo is complete */
    object DemoComplete : DemoAction()
}

/**
 * Creates the "release-notes" demo scenario event stream.
 *
 * This demo showcases:
 * - Logo crystallization
 * - Two agents (Spark and Jazz) spawning
 * - Task delegation and handoff
 * - Output generation
 */
fun createReleaseNotesMockEvents(): List<MockEvent> = listOf(
    // Logo formation
    MockEvent(0.0.seconds, DemoAction.LogoStart),

    // Agents spawn
    MockEvent(2.5.seconds, DemoAction.AgentSpawned("spark", "Spark", "reasoning")),
    MockEvent(3.0.seconds, DemoAction.AgentSpawned("jazz", "Jazz", "codegen")),

    // Task creation and initial work
    MockEvent(4.0.seconds, DemoAction.TaskCreated(
        taskId = "summarize-commits",
        description = "Summarize recent commits",
        assignedTo = "spark"
    )),
    MockEvent(4.2.seconds, DemoAction.AgentActivated("spark")),
    MockEvent(4.5.seconds, DemoAction.AgentStatusChanged("spark", "analyzing git history...")),
    MockEvent(5.5.seconds, DemoAction.AgentStatusChanged("spark", "identifying key changes...")),
    MockEvent(7.0.seconds, DemoAction.AgentStatusChanged("spark", "delegating to Jazz...")),

    // Handoff
    MockEvent(7.5.seconds, DemoAction.HandoffStarted("spark", "jazz")),
    MockEvent(8.5.seconds, DemoAction.TaskCreated(
        taskId = "draft-notes",
        description = "Draft release notes",
        assignedTo = "jazz"
    )),
    MockEvent(9.0.seconds, DemoAction.HandoffCompleted("spark", "jazz")),
    MockEvent(9.2.seconds, DemoAction.AgentIdle("spark")),
    MockEvent(9.5.seconds, DemoAction.AgentActivated("jazz")),
    MockEvent(10.0.seconds, DemoAction.AgentStatusChanged("jazz", "generating markdown...")),

    // Output streaming
    MockEvent(12.0.seconds, DemoAction.OutputChunk("## v0.1.1 Release Notes\n\n")),
    MockEvent(12.5.seconds, DemoAction.OutputChunk("### Features\n")),
    MockEvent(13.0.seconds, DemoAction.OutputChunk("- Multi-agent coordination\n")),
    MockEvent(13.5.seconds, DemoAction.OutputChunk("- Task handoff visualization\n")),
    MockEvent(14.0.seconds, DemoAction.OutputChunk("- Substrate animation system\n\n")),
    MockEvent(14.5.seconds, DemoAction.OutputChunk("### Bug Fixes\n")),
    MockEvent(15.0.seconds, DemoAction.OutputChunk("- Fixed memory leak in particle system\n")),

    // Completion
    MockEvent(16.0.seconds, DemoAction.TaskCompleted("draft-notes")),
    MockEvent(16.2.seconds, DemoAction.AgentComplete("jazz")),
    MockEvent(16.5.seconds, DemoAction.TaskCompleted("summarize-commits")),
    MockEvent(16.7.seconds, DemoAction.AgentComplete("spark")),

    // Stats
    MockEvent(17.5.seconds, DemoAction.ShowStats("2 agents · 2 tasks · 17.5s")),
    MockEvent(19.0.seconds, DemoAction.DemoComplete)
)

/**
 * Creates the "code-review" demo scenario event stream.
 *
 * This demo showcases:
 * - Three agents (Architect, Reviewer, Coder)
 * - Multiple handoffs
 * - Code review workflow
 */
fun createCodeReviewMockEvents(): List<MockEvent> = listOf(
    // Logo formation
    MockEvent(0.0.seconds, DemoAction.LogoStart),

    // Agents spawn
    MockEvent(2.5.seconds, DemoAction.AgentSpawned("architect", "Architect", "planning")),
    MockEvent(3.0.seconds, DemoAction.AgentSpawned("reviewer", "Reviewer", "analysis")),
    MockEvent(3.5.seconds, DemoAction.AgentSpawned("coder", "Coder", "codegen")),

    // Planning phase
    MockEvent(4.5.seconds, DemoAction.TaskCreated(
        taskId = "design-api",
        description = "Design API structure",
        assignedTo = "architect"
    )),
    MockEvent(4.7.seconds, DemoAction.AgentActivated("architect")),
    MockEvent(5.0.seconds, DemoAction.AgentStatusChanged("architect", "analyzing requirements...")),
    MockEvent(6.5.seconds, DemoAction.AgentStatusChanged("architect", "designing interfaces...")),

    // Handoff to coder
    MockEvent(8.0.seconds, DemoAction.HandoffStarted("architect", "coder")),
    MockEvent(8.5.seconds, DemoAction.TaskCreated(
        taskId = "implement-api",
        description = "Implement API endpoints",
        assignedTo = "coder"
    )),
    MockEvent(9.0.seconds, DemoAction.HandoffCompleted("architect", "coder")),
    MockEvent(9.2.seconds, DemoAction.AgentIdle("architect")),
    MockEvent(9.5.seconds, DemoAction.AgentActivated("coder")),
    MockEvent(10.0.seconds, DemoAction.AgentStatusChanged("coder", "writing endpoints...")),
    MockEvent(12.0.seconds, DemoAction.AgentStatusChanged("coder", "adding tests...")),

    // Handoff to reviewer
    MockEvent(14.0.seconds, DemoAction.HandoffStarted("coder", "reviewer")),
    MockEvent(14.5.seconds, DemoAction.TaskCreated(
        taskId = "review-code",
        description = "Review code quality",
        assignedTo = "reviewer"
    )),
    MockEvent(15.0.seconds, DemoAction.HandoffCompleted("coder", "reviewer")),
    MockEvent(15.2.seconds, DemoAction.AgentComplete("coder")),
    MockEvent(15.5.seconds, DemoAction.AgentActivated("reviewer")),
    MockEvent(16.0.seconds, DemoAction.AgentStatusChanged("reviewer", "checking style...")),
    MockEvent(17.0.seconds, DemoAction.AgentStatusChanged("reviewer", "verifying tests...")),
    MockEvent(18.0.seconds, DemoAction.AgentStatusChanged("reviewer", "approved ✓")),

    // Completion
    MockEvent(18.5.seconds, DemoAction.TaskCompleted("review-code")),
    MockEvent(18.7.seconds, DemoAction.AgentComplete("reviewer")),
    MockEvent(19.0.seconds, DemoAction.TaskCompleted("implement-api")),
    MockEvent(19.2.seconds, DemoAction.TaskCompleted("design-api")),
    MockEvent(19.5.seconds, DemoAction.AgentComplete("architect")),

    // Stats
    MockEvent(20.0.seconds, DemoAction.ShowStats("3 agents · 3 tasks · 2 handoffs · 20s")),
    MockEvent(21.5.seconds, DemoAction.DemoComplete)
)
