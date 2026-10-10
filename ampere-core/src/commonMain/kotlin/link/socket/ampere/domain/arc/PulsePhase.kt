package link.socket.ampere.domain.arc

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.Agent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.trace.ArcRunId

data class PulseResult(
    val success: Boolean,
    val commitSha: String?,
    val prUrl: String?,
    val learnings: List<Learning>,
    val evaluationReport: EvaluationReport,
)

/**
 * One `Knowledge` cell Pulse distilled from one successful outcome, and whether it was persisted.
 *
 * @property stored True once the cell reached the agent's [link.socket.ampere.agents.domain.memory.AgentMemoryService]
 *   and its `KnowledgeStored` event reached the agent's door. False means the loop did not close
 *   for this learning — the agent has no memory service (no `KnowledgeRepository` was wired into
 *   the run), it is not among the agents handed to [PulsePhase], or the write failed. Read it
 *   rather than inferring persistence from [PulseResult.success], which reports only whether the
 *   Arc met its success criteria.
 */
data class Learning(
    val agentId: String,
    val knowledge: Knowledge,
    val context: String,
    val stored: Boolean = false,
)

data class EvaluationReport(
    val goalsCompleted: Int,
    val goalsTotal: Int,
    val testsRun: Boolean,
    val testsPassed: Boolean,
    val successfulOutcomes: Int,
    val failedOutcomes: Int,
    val recommendations: List<String>,
)

/**
 * The Arc's closing phase: evaluate the run against its success criteria, and close the PROPEL
 * loop by distilling each successful outcome into a `Knowledge` cell and *storing* it.
 *
 * Storing is the point. Building the cells and returning them on [PulseResult] left every
 * successful Arc run with nothing a later Recall could find (AMPR-402); the write is what makes
 * the loop autocatalytic rather than open. It needs two things from the caller:
 *
 * - [agents], so each learning is written through the door and memory service of the agent whose
 *   outcome produced it. That is the F9/F11 provenance anchor: `KnowledgeStored` names the holder,
 *   not the runtime. An agent missing from this list gets its learning built and left unstored.
 * - [runId], so the entry and its event carry the run that produced them and
 *   `ArcTraceProjection` can read the write back under that run.
 *
 * A run whose agents have no `AgentMemoryService` — no `KnowledgeRepository` was wired into the
 * runtime — still produces learnings, each marked `stored = false`. That is the honest report:
 * the phase ran, the cells exist, nothing persisted them.
 *
 * Only the success path writes. A run that is cancelled or throws does not reach Pulse at all and
 * owes a `CompletionManifest` instead of a `Knowledge` entry (AMPR-282) — see
 * [AmpereRuntime]. Cancellation that lands *while* Pulse is writing is not held off: the
 * remaining writes are abandoned, because a run that did not finish closing its loop should not
 * be credited with having closed it.
 */
class PulsePhase(
    private val arcConfig: ArcConfig,
    private val flowResult: FlowResult,
    private val projectContext: ProjectContext,
    private val goalTree: GoalTree,
    private val clock: Clock = Clock.System,
    /**
     * The agents this run spawned, matched to [FlowResult.agentOutcomes] by [Agent.id]. Empty
     * leaves every learning unstored, which is the pre-AMPR-402 behaviour and is why this
     * defaults rather than being required.
     */
    private val agents: List<Agent<*>> = emptyList(),
    /** The run every stored learning is tagged with. Null stores entries with no `run_id`. */
    private val runId: ArcRunId? = null,
) {
    suspend fun execute(): PulseResult {
        // 1. Evaluate success criteria
        val evaluation = evaluateCompletion()

        // 2. Deliver if successful (commit and PR creation happens externally via GitCliProvider)
        // For MVP, we just return the evaluation results
        // Git operations will be handled by the runtime/orchestrator

        // 3. Capture learnings
        val learnings = captureLearnings()

        // 4. Cleanup is handled by the caller
        // (archiving trace data, clearing state, etc.)

        return PulseResult(
            success = evaluation.isSuccessful(),
            commitSha = null, // Populated externally
            prUrl = null, // Populated externally
            learnings = learnings,
            evaluationReport = evaluation,
        )
    }

    private fun evaluateCompletion(): EvaluationReport {
        val goalsTotal = goalTree.allNodes().size
        val goalsCompleted = flowResult.completedGoals.size

        // Count outcomes by type
        var successfulOutcomes = 0
        var failedOutcomes = 0

        flowResult.agentOutcomes.values.forEach { outcomes ->
            outcomes.forEach { outcome ->
                when (outcome) {
                    is Outcome.Success -> successfulOutcomes++
                    is Outcome.Failure -> failedOutcomes++
                    else -> {} // Ignore other outcome types
                }
            }
        }

        // Determine if tests were run (heuristic: check if any agent is QA/testing role)
        val testsRun = arcConfig.agents.any { agent ->
            agent.role.lowercase() in setOf("qa", "quality", "validator", "test")
        }

        // Determine if tests passed (if tests were run, check for failed outcomes)
        val testsPassed = if (testsRun) failedOutcomes == 0 else true

        // Generate recommendations
        val recommendations = buildRecommendations(
            goalsCompleted = goalsCompleted,
            goalsTotal = goalsTotal,
            failedOutcomes = failedOutcomes,
            testsRun = testsRun,
            testsPassed = testsPassed,
        )

        return EvaluationReport(
            goalsCompleted = goalsCompleted,
            goalsTotal = goalsTotal,
            testsRun = testsRun,
            testsPassed = testsPassed,
            successfulOutcomes = successfulOutcomes,
            failedOutcomes = failedOutcomes,
            recommendations = recommendations,
        )
    }

    private fun buildRecommendations(
        goalsCompleted: Int,
        goalsTotal: Int,
        failedOutcomes: Int,
        testsRun: Boolean,
        testsPassed: Boolean,
    ): List<String> {
        val recommendations = mutableListOf<String>()

        if (goalsCompleted < goalsTotal) {
            recommendations.add(
                "Not all goals completed ($goalsCompleted/$goalsTotal). Consider running another Flow phase iteration.",
            )
        }

        if (failedOutcomes > 0) {
            recommendations.add("$failedOutcomes failed outcomes detected. Review errors before delivery.")
        }

        if (!testsRun) {
            recommendations.add("No tests were run. Consider adding a QA agent to validate changes.")
        }

        if (testsRun && !testsPassed) {
            recommendations.add("Tests failed. Fix failing tests before creating PR.")
        }

        if (recommendations.isEmpty()) {
            recommendations.add("All success criteria met. Ready for delivery.")
        }

        return recommendations
    }

    /**
     * Distil one `Knowledge` cell per successful outcome and persist each one through the agent
     * that produced it.
     *
     * Capture and store are one pass on purpose: the outcome in hand is what decides the entry's
     * task type, and splitting the two invites a build step that returns cells nobody writes —
     * exactly the shape this phase had before.
     */
    private suspend fun captureLearnings(): List<Learning> {
        val agentsById = agents.associateBy { it.id }
        val learnings = mutableListOf<Learning>()

        flowResult.agentOutcomes.forEach { (agentId, outcomes) ->
            outcomes.forEach { outcome ->
                // Create knowledge from each successful outcome
                if (outcome is Outcome.Success) {
                    val knowledge = Knowledge.FromOutcome(
                        outcomeId = outcome.id,
                        approach = "Arc: ${arcConfig.name}",
                        learnings = "Agent $agentId completed task successfully",
                        timestamp = clock.now(),
                    )

                    // Through the agent, not through a repository held here: the agent owns the
                    // door `KnowledgeStored` leaves by, so the event names the holder.
                    val stored = agentsById[agentId]?.storeKnowledge(
                        knowledge = knowledge,
                        tags = listOf(ARC_TAG, arcConfig.name, SUCCESS_TAG),
                        taskType = taskTypeFor(outcome),
                        runId = runId,
                    )?.isSuccess ?: false

                    learnings.add(
                        Learning(
                            agentId = agentId,
                            knowledge = knowledge,
                            context = "Arc: ${arcConfig.name}, Project: ${projectContext.projectId}",
                            stored = stored,
                        ),
                    )
                }
            }
        }

        return learnings
    }

    /**
     * The retrieval bucket a learning is filed under.
     *
     * Drawn from [MemoryTaskTypes] rather than invented here, because Recall matches on this
     * string: `FlowPhase` asks for [MemoryTaskTypes.CODE_CHANGE] or [MemoryTaskTypes.GENERIC], so
     * anything else Pulse wrote would be stored and never read.
     */
    private fun taskTypeFor(outcome: Outcome): String = when (outcome) {
        is ExecutionOutcome.CodeChanged -> MemoryTaskTypes.CODE_CHANGE
        else -> MemoryTaskTypes.GENERIC
    }

    private fun EvaluationReport.isSuccessful(): Boolean {
        return goalsCompleted == goalsTotal &&
            failedOutcomes == 0 &&
            (!testsRun || testsPassed)
    }

    private companion object {
        /** Marks every entry an Arc's Pulse wrote, so they can be pulled back as a set. */
        const val ARC_TAG = "arc"

        /** Pulse only distils successful outcomes; the tag says so explicitly to a reader. */
        const val SUCCESS_TAG = "success"
    }
}
