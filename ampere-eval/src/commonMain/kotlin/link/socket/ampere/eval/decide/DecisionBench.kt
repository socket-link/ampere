package link.socket.ampere.eval.decide

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.stateDigest

/**
 * Runs labelled [DecisionCase]s through [DecisionArm]s and reports how each arm did (AMPR-422).
 *
 * `Bench` drives an Arc and grades a `Trace`; this drives nothing but a decision client, one
 * case at a time, and grades answers against labels. Per case, arm and repeat it records one
 * [DecisionRecord]: the answer, the distribution when there is one, the latency and the usage.
 * A client that throws produces a record with the error on it rather than aborting the run —
 * a transport that cannot answer some cases is one of the things being measured.
 *
 * Every case is asked as its own [DecisionRequest] with one question under [questionId], the
 * way a hosted run asks its goal question: one state, one question, one call.
 *
 * A [PlaybackDecisionClient] is served by case id and repeat rather than through `decide`, so
 * a replay never depends on digest bookkeeping. Every other client is called through the seam
 * exactly as a hosted seat would call it.
 *
 * @param questionId The id the one question is asked under. `goal_met` for the goal-met bench.
 * @param timeSource What latency is measured with.
 */
class DecisionBench(
    private val questionId: String = DEFAULT_QUESTION_ID,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /**
     * Ask every case of every arm [repeats] times.
     *
     * @param positiveKeys The answer keys the report reads as "met" — `true` for the shipped
     *   noul, `met` for the three-option choice. A case is positive when its expected key is
     *   one of them; a call is positive when its answer is.
     * @param parallelism How many calls may be in flight at once per arm. Arms run one after
     *   another; cases of one arm may overlap up to this many. One for a deterministic arm,
     *   a handful for a hosted one.
     * @param onRecord Called with each record as soon as it exists, in no particular order
     *   when [parallelism] is above one. A live run appends to its file here, so an
     *   interrupted run keeps what it had.
     */
    suspend fun run(
        cases: List<DecisionCase>,
        arms: List<DecisionArm>,
        repeats: Int = 1,
        positiveKeys: Set<String> = setOf(Question.Noul.TRUE_KEY),
        parallelism: Int = 1,
        onRecord: ((DecisionRecord) -> Unit)? = null,
    ): DecisionBenchReport {
        require(repeats >= 1) { "A bench run asks each case at least once; got repeats=$repeats" }
        require(parallelism >= 1) { "Parallelism is at least one; got $parallelism" }
        require(cases.map { it.id }.toSet().size == cases.size) { "Case ids must be unique" }
        require(arms.map { it.id }.toSet().size == arms.size) { "Arm ids must be unique" }

        val records = mutableListOf<DecisionRecord>()
        for (arm in arms) {
            val gate = Semaphore(parallelism)
            val armRecords = coroutineScope {
                (0 until repeats).flatMap { repeat ->
                    cases.map { case ->
                        async {
                            gate.withPermit { ask(arm, case, repeat).also { onRecord?.invoke(it) } }
                        }
                    }
                }.awaitAll()
            }
            records += armRecords
        }
        return DecisionBenchReport(
            cases = cases,
            armIds = arms.map { it.id },
            repeats = repeats,
            records = records,
            positiveKeys = positiveKeys,
        )
    }

    private suspend fun ask(arm: DecisionArm, case: DecisionCase, repeat: Int): DecisionRecord {
        val request = DecisionRequest(state = case.state, questions = mapOf(questionId to case.question))
        val digest = stateDigest(case.state)
        val started = timeSource.markNow()
        return try {
            val client = arm.client
            val response = if (client is PlaybackDecisionClient) {
                client.serve(case.id, repeat, request)
            } else {
                client.decide(request, arm.configuration)
            }
            val latency = started.elapsedNow().inWholeMilliseconds
            val judgment = response.judgments[questionId]
                ?: return DecisionRecord.failed(
                    caseId = case.id,
                    armId = arm.id,
                    repeat = repeat,
                    stateDigest = digest,
                    error = "response carried no judgment under '$questionId'; had ${response.judgments.keys}",
                    latencyMs = latency,
                )
            DecisionRecord.of(
                caseId = case.id,
                armId = arm.id,
                repeat = repeat,
                stateDigest = digest,
                judgment = judgment,
                latencyMs = latency,
                usage = response.usage,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            DecisionRecord.failed(
                caseId = case.id,
                armId = arm.id,
                repeat = repeat,
                stateDigest = digest,
                error = "${failure::class.simpleName}: ${failure.message}",
                latencyMs = started.elapsedNow().inWholeMilliseconds,
            )
        }
    }

    companion object {
        /** The question id used when none is given. */
        const val DEFAULT_QUESTION_ID: String = "goal_met"
    }
}
