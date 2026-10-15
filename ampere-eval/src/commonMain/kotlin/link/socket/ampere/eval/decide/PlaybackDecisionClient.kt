package link.socket.ampere.eval.decide

import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.DecisionResponse
import link.socket.ampere.llm.decide.MalformedDecisionResponseException
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.llm.decide.stateDigest

/**
 * An [UpstreamDecisionClient] that serves recorded answers (AMPR-422).
 *
 * The decision-seam sibling of `PlaybackRelay`: a live arm is run once, its [DecisionRecord]s
 * are committed, and from then on the bench replays them offline — the report's numbers are
 * reproducible from the repository with no network and no key. A miss is a hard error, for the
 * reason the relay's is: a playback client that answered something it never recorded would be
 * measuring itself.
 *
 * Two ways to be asked:
 *
 * - [serve] by case id and repeat — what [DecisionBench] does, because it knows both.
 * - [decide] by state — the seam's own method. The record is found by the state's digest, and
 *   successive calls on the same state serve successive repeats, so the client can stand in
 *   for a transport inside code that does not know it is being replayed.
 *
 * A recorded *failure* replays as a failure: the call throws
 * [MalformedDecisionResponseException] with the recorded message, so a transport that could
 * not answer a case live cannot answer it in replay either.
 *
 * @param records The recorded answers, from one or more arms. A record is keyed by
 *   `(armId, caseId, repeat)`; when [armId] is null every arm's records are served and the
 *   key is `(caseId, repeat)`, which must then be unique across them.
 */
class PlaybackDecisionClient(
    records: List<DecisionRecord>,
    private val armId: String? = null,
) : UpstreamDecisionClient {

    private val byCaseAndRepeat: Map<Pair<String, Int>, DecisionRecord>
    private val byDigest: Map<String, List<DecisionRecord>>
    private val cursors = mutableMapOf<String, Int>()

    init {
        val mine = if (armId == null) records else records.filter { it.armId == armId }
        val keyed = mutableMapOf<Pair<String, Int>, DecisionRecord>()
        for (record in mine) {
            val key = record.caseId to record.repeat
            require(keyed.put(key, record) == null) {
                "Two recorded answers for case '${record.caseId}' repeat ${record.repeat}" +
                    (armId?.let { " on arm '$it'" } ?: " across arms; pass an armId")
            }
        }
        byCaseAndRepeat = keyed
        byDigest = mine.groupBy { it.stateDigest }.mapValues { (_, group) -> group.sortedBy { it.repeat } }
    }

    /** Every record this client can serve. */
    val records: Collection<DecisionRecord>
        get() = byCaseAndRepeat.values

    /** The recorded answer to [caseId] on [repeat], as the response the live arm gave. */
    fun serve(caseId: String, repeat: Int, request: DecisionRequest): DecisionResponse {
        val record = byCaseAndRepeat[caseId to repeat]
            ?: throw PlaybackDecisionMiss("No recorded answer for case '$caseId' repeat $repeat${armSuffix()}")
        return respond(record, request)
    }

    override suspend fun decide(
        request: DecisionRequest,
        configuration: AIConfiguration,
    ): DecisionResponse {
        val digest = stateDigest(request.state)
        val recorded = byDigest[digest]
            ?: throw PlaybackDecisionMiss("No recorded answer for a state with digest $digest${armSuffix()}")
        val index = cursors.getOrElse(digest) { 0 }
        val record = recorded.getOrNull(index)
            ?: throw PlaybackDecisionMiss(
                "State with digest $digest was recorded ${recorded.size} time(s) and has been asked " +
                    "${index + 1} time(s)${armSuffix()}",
            )
        cursors[digest] = index + 1
        return respond(record, request)
    }

    private fun respond(record: DecisionRecord, request: DecisionRequest): DecisionResponse {
        val judgment = record.toJudgment()
            ?: throw MalformedDecisionResponseException(record.error ?: "recorded call failed without a message")
        require(request.questions.size == 1) {
            "A playback client serves one question per request; got ${request.questions.keys}"
        }
        val (id, question) = request.questions.entries.first()
        if (judgment.answer !in question.answerKeys) {
            throw PlaybackDecisionMiss(
                "Recorded answer '${judgment.answer}' for case '${record.caseId}' is not one of the " +
                    "question's answers ${question.answerKeys}; the question changed since it was recorded",
            )
        }
        return DecisionResponse(judgments = mapOf(id to judgment), usage = record.usage)
    }

    private fun armSuffix(): String = armId?.let { " on arm '$it'" }.orEmpty()
}

/** A [PlaybackDecisionClient] was asked something it has no recording of. */
class PlaybackDecisionMiss(message: String) : IllegalStateException(message)
