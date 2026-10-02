package link.socket.ampere.roster

import link.socket.ampere.agents.execution.tools.ASK_HUMAN_TOOL_ID
import link.socket.ampere.agents.execution.tools.CREATE_ISSUES_TOOL_ID
import link.socket.ampere.agents.execution.tools.planning.PLAN_STEPS_TOOL_ID
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.tools.KNOWLEDGE_QUERY_TOOL_ID

/**
 * The six roles that plan and tend a Blueprint — the first consumer of the team
 * layer (AMPR-379, Socket decision D31).
 *
 * The roster is a value in `ampere-core` because Ampere owns roles, the Room, its
 * threads, and the Meeting; Socket renders the Room and fills the seats. The
 * Blueprint vocabulary is confined to [RosterPrompts]; nothing here names a part or
 * a finish.
 *
 * Review edges: the Inspector reviews the Planner and the Scout — their cards go
 * through `ReviewGate` before they reach the Room — and nobody reviews the
 * Inspector. The graph is acyclic by construction and pinned by test.
 *
 * `Crew` is not reused: it is not an Ampere type, and the collective noun the user
 * sees is simply "agents".
 */
object BlueprintRoster : Roster {

    /** Decomposes the goal into milestones and tasks; owns the dependency graph. */
    val planner: RoleConfig = RoleConfig(
        id = RoleId("planner"),
        title = "Planner",
        instructions = RosterPrompts.PLANNER,
        tools = setOf(PLAN_STEPS_TOOL_ID, CREATE_ISSUES_TOOL_ID, ASK_HUMAN_TOOL_ID),
    )

    /** Durations per task; reads the `EstimateCalibrationSource` in its Recall step. */
    val estimator: RoleConfig = RoleConfig(
        id = RoleId("estimator"),
        title = "Estimator",
        instructions = RosterPrompts.ESTIMATOR,
        tools = setOf(KNOWLEDGE_QUERY_TOOL_ID),
    )

    /** Parts, guides, lead times — via the consumer's search and fetch plug. */
    val scout: RoleConfig = RoleConfig(
        id = RoleId("scout"),
        title = "Scout",
        instructions = RosterPrompts.SCOUT,
        tools = setOf(RosterTools.WEB_SEARCH, RosterTools.WEB_FETCH),
    )

    /** Proposes sessions against availability windows. */
    val scheduler: RoleConfig = RoleConfig(
        id = RoleId("scheduler"),
        title = "Scheduler",
        instructions = RosterPrompts.SCHEDULER,
        tools = setOf(PLAN_STEPS_TOOL_ID),
    )

    /** Runs the ProbeSuite and posts Verdict cards; reviews Planner and Scout output. */
    val inspector: RoleConfig = RoleConfig(
        id = RoleId("inspector"),
        title = "Inspector",
        instructions = RosterPrompts.INSPECTOR,
        tools = emptySet(),
        reviews = setOf(planner.id, scout.id),
    )

    /** Hosts the Room, runs the Standup, and is the only role that DMs the human. */
    val coordinator: RoleConfig = RoleConfig(
        id = RoleId("coordinator"),
        title = "Coordinator",
        instructions = RosterPrompts.COORDINATOR,
        tools = setOf(ASK_HUMAN_TOOL_ID),
    )

    override fun all(): List<RoleConfig> = listOf(planner, estimator, scout, scheduler, inspector, coordinator)

    override val host: RoleId get() = coordinator.id

    override val verifier: RoleId get() = inspector.id

    /**
     * A sequence verdict is the Planner's to resolve (the graph is wrong); every
     * other Probe judges a fact about a part or a source, which is the Scout's.
     */
    override fun resolverFor(probeId: ProbeId): RoleId =
        if (probeId.value == SequenceProbe.ID) planner.id else scout.id
}
