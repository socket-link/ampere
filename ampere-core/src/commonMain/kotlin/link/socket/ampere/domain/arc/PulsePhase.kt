package link.socket.ampere.domain.arc

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.Outcome

data class PulseResult(
    val success: Boolean,
    val commitSha: String?,
    val prUrl: String?,
    val learnings: List<Learning>,
    val evaluationReport: EvaluationReport,
)

data class Learning(
    val agentId: String,
    val knowledge: Knowledge,
    val context: String,
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

class PulsePhase(
    private val arcConfig: ArcConfig,
    private val flowResult: FlowResult,
    private val projectContext: ProjectContext,
    private val goalTree: GoalTree,
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
            recommendations.add("Not all goals completed ($goalsCompleted/$goalsTotal). Consider running another Flow phase iteration.")
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

    private fun captureLearnings(): List<Learning> {
        // Extract learnings from agent outcomes
        val learnings = mutableListOf<Learning>()

        flowResult.agentOutcomes.forEach { (agentId, outcomes) ->
            outcomes.forEach { outcome ->
                // Create knowledge from each successful outcome
                if (outcome is Outcome.Success) {
                    val knowledge = Knowledge.FromOutcome(
                        outcomeId = outcome.id,
                        approach = "Arc: ${arcConfig.name}",
                        learnings = "Agent $agentId completed task successfully",
                        timestamp = Clock.System.now(),
                    )

                    learnings.add(
                        Learning(
                            agentId = agentId,
                            knowledge = knowledge,
                            context = "Arc: ${arcConfig.name}, Project: ${projectContext.projectId}",
                        ),
                    )
                }
            }
        }

        return learnings
    }

    private fun EvaluationReport.isSuccessful(): Boolean {
        return goalsCompleted == goalsTotal &&
            failedOutcomes == 0 &&
            (!testsRun || testsPassed)
    }
}
