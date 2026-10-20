package link.socket.ampere.eval.decide

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.decide.stateDigest

/**
 * AMPR-482 tasks 3 and 4 validation: every recorded file replays through
 * [PlaybackDecisionClient] and reproduces the report offline, and the document's tables are
 * regenerated from the recordings and match. Also writes the full rendered report to
 * `build/reports/decide/perception-drift.md`, which is what
 * `docs/ampr-482-perception-drift-bench.md` quotes.
 *
 * The headline numbers pinned below are the ones the document states. A re-record that moves
 * them fails here, which is the point: the document and the repository must agree.
 */
class PerceptionDriftRecordedBenchTest {

    private val corpus = PerceptionDriftCorpus.load()
    private val order = corpus.withIndex().associate { (index, case) -> case.id to index }
    private val configuration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

    /** The recorded files, by arm id. */
    private val recorded: Map<String, List<DecisionRecord>> = recordedDirectory()
        ?.let { directory -> Files.list(directory).use { stream -> stream.toList() } }
        ?.filter { it.name.endsWith(".jsonl") }
        .orEmpty()
        .sortedBy { it.name }
        .associate { file -> file.name.removeSuffix(".jsonl") to DecisionRecord.decodeLines(file.readText()) }

    private fun recordedDirectory(): Path? {
        val path = "/${PerceptionDriftCorpus.RESOURCE_DIR}/${PerceptionDriftBenchLive.RECORDED_DIR}/"
        val resource = PerceptionDriftRecordedBenchTest::class.java.getResource(path)
        val fromClasspath = resource?.takeIf { it.protocol == "file" }?.let { Paths.get(it.toURI()) }
        val fromSource = Paths.get(
            "src/jvmTest/resources",
            PerceptionDriftCorpus.RESOURCE_DIR,
            PerceptionDriftBenchLive.RECORDED_DIR,
        )
        return listOfNotNull(fromClasspath, fromSource).firstOrNull { Files.isDirectory(it) }
    }

    private fun formOf(armId: String): PerceptionDriftForm =
        PerceptionDriftForm.entries.first { armId.endsWith(".${it.wireName}") }

    private fun replay(armId: String, records: List<DecisionRecord>): DecisionBenchReport = runBlocking {
        val form = formOf(armId)
        val repeats = records.maxOf { it.repeat } + 1
        DecisionBench(PerceptionDriftBenchLive.QUESTION_ID).run(
            cases = corpus.map(form::of),
            arms = listOf(DecisionArm(armId, PlaybackDecisionClient(records, armId), configuration)),
            repeats = repeats,
            positiveKeys = form.settledKeys,
        )
    }

    @Test
    fun `every recorded answer is keyed to a case of the committed corpus`() {
        val digests = corpus.associate { it.id to stateDigest(it.state) }
        recorded.forEach { (armId, records) ->
            records.forEach { record ->
                // Three beside kinds of a family share one state, so the join is id → digest, not digest → id.
                assertEquals(digests[record.caseId], record.stateDigest, "$armId: ${record.caseId} is another state")
                assertEquals(armId, record.armId)
            }
            assertEquals(corpus.size * (records.maxOf { it.repeat } + 1), records.size, "$armId is complete")
        }
    }

    @Test
    fun `every recorded file replays offline and the replay is itself reproducible`() {
        recorded.forEach { (armId, records) ->
            val first = replay(armId, records)
            val second = replay(armId, records)
            val misses = first.missing(armId).count { "PlaybackDecisionMiss" in it.error.orEmpty() }
            assertEquals(0, misses, "$armId replays without a miss")
            assertEquals(first.confusion(armId), second.confusion(armId), armId)
            assertEquals(first.sweep(armId), second.sweep(armId), armId)
            assertEquals(
                records.map { it.answer },
                first.records.sortedWith(compareBy({ it.repeat }, { order.getValue(it.caseId) })).map { it.answer },
                "$armId: the replay answers what was recorded, in order",
            )
        }
    }

    @Test
    fun `the headline numbers of the report reproduce`() {
        assertEquals(HEADLINES.keys, recorded.keys, "every recorded arm is quoted, and every quoted arm is recorded")
        HEADLINES.forEach { (armId, expected) ->
            val report = replay(armId, recorded.getValue(armId))
            val confusion = PerceptionDriftReport.confusion(report, armId)
            assertEquals(expected.falseSettled, confusion.falseSettled, "$armId false settled")
            assertEquals(expected.falseMoved, confusion.falseMoved, "$armId false moved")
            assertEquals(expected.missing, confusion.missing, "$armId missing")
            assertEquals(expected.fittable, report.isFittable(armId), "$armId fittable")
            assertEquals(expected.agreeingCases, report.stability(armId).agreeingCases, "$armId repeat agreement")
        }
    }

    @Test
    fun `the document quotes the recorded numbers`() {
        if (HEADLINES.isEmpty()) return
        val document = listOf(Paths.get("../$DOCUMENT"), Paths.get(DOCUMENT))
            .firstOrNull { Files.exists(it) }
            ?.readText()
            ?: throw AssertionError("$DOCUMENT is missing")
        HEADLINES.forEach { (armId, expected) ->
            val report = replay(armId, recorded.getValue(armId))
            val confusion = PerceptionDriftReport.confusion(report, armId)
            val falseSettled = "${expected.falseSettled} / ${confusion.bearsCases}"
            val falseMoved = "${expected.falseMoved} / ${confusion.besideCases}"
            assertTrue(falseSettled in document, "$armId: the document quotes false settled as '$falseSettled'")
            assertTrue(falseMoved in document, "$armId: the document quotes false moved as '$falseMoved'")
        }
    }

    @Test
    fun `renders the full report for the document`() {
        val arms = PerceptionDriftArms.all(PerceptionDriftCorpus.OWN_NAMES)
            .map { (id, client) -> DecisionArm(id, client, configuration) }
        val deterministic = runBlocking {
            DecisionBench(PerceptionDriftBenchLive.QUESTION_ID).run(
                cases = corpus,
                arms = arms,
                repeats = 1,
                positiveKeys = PerceptionDriftForm.Noul.settledKeys,
            )
        }
        val sections = mutableListOf(
            PerceptionDriftReport.render(
                deterministic,
                "Perception-drift bench — deterministic arms (noul, 1 repeat)",
            ),
        )
        recorded.forEach { (armId, records) ->
            sections += PerceptionDriftReport.render(
                replay(armId, records),
                "Perception-drift bench — `$armId` (replay of recordings/$armId.jsonl)",
            )
        }
        val output = Paths.get("build/reports/decide")
        Files.createDirectories(output)
        Files.writeString(output.resolve("perception-drift.md"), sections.joinToString("\n---\n\n"))
        assertTrue(Files.size(output.resolve("perception-drift.md")) > 0)
    }

    /** The counts `docs/ampr-482-perception-drift-bench.md` quotes, per recorded arm. */
    private data class Headline(
        val falseSettled: Int,
        val falseMoved: Int,
        val missing: Int,
        val fittable: Boolean,
        val agreeingCases: Int,
    )

    private companion object {
        const val DOCUMENT = "docs/ampr-482-perception-drift-bench.md"

        /**
         * From the AMPR-486 re-record of 2026-10-20, over the state that renders a goal change's
         * `relation` and a commitment's `about` (three repeats, 112 cases, 168 bears and 168
         * beside calls per arm). The document's §3.3; §3.2's numbers are of the earlier state.
         */
        val HEADLINES: Map<String, Headline> = mapOf(
            "claude-sonnet-5.noul" to
                Headline(falseSettled = 53, falseMoved = 0, missing = 0, fittable = false, agreeingCases = 109),
            "claude-sonnet-5.choice" to
                Headline(falseSettled = 45, falseMoved = 1, missing = 0, fittable = false, agreeingCases = 107),
        )
    }
}
