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
 * AMPR-422 task 5 validation: every recorded file replays through [PlaybackDecisionClient]
 * and reproduces the report offline. Also writes the full rendered report to
 * `build/reports/decide/goal-met.md`, which is what `docs/ampr-422-goal-met-bench.md` quotes.
 *
 * The headline numbers pinned below are the ones the report states. A re-record that moves
 * them fails here, which is the point: the document and the repository must agree.
 */
class GoalMetRecordedBenchTest {

    private val corpus = GoalMetCorpus.load()
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
        val path = "/${GoalMetCorpus.RESOURCE_DIR}/${DecideBenchLive.RECORDED_DIR}/"
        val resource = GoalMetRecordedBenchTest::class.java.getResource(path)
        val fromClasspath = resource?.takeIf { it.protocol == "file" }?.let { Paths.get(it.toURI()) }
        val fromSource = Paths.get("src/jvmTest/resources", GoalMetCorpus.RESOURCE_DIR, DecideBenchLive.RECORDED_DIR)
        return listOfNotNull(fromClasspath, fromSource).firstOrNull { Files.isDirectory(it) }
    }

    private fun formOf(armId: String): GoalMetForm = GoalMetForm.entries.first { armId.endsWith(".${it.wireName}") }

    private fun replay(armId: String, records: List<DecisionRecord>): DecisionBenchReport = runBlocking {
        val form = formOf(armId)
        val repeats = records.maxOf { it.repeat } + 1
        DecisionBench(DecideBenchLive.QUESTION_ID).run(
            cases = corpus.map(form::of),
            arms = listOf(DecisionArm(armId, PlaybackDecisionClient(records, armId), configuration)),
            repeats = repeats,
            positiveKeys = form.positiveKeys,
        )
    }

    @Test
    fun `every recorded answer is keyed to a case of the committed corpus`() {
        val digests = corpus.associate { stateDigest(it.state) to it.id }
        recorded.forEach { (armId, records) ->
            records.forEach { record ->
                assertEquals(digests[record.stateDigest], record.caseId, "$armId: ${record.caseId} is another state")
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
        HEADLINES.forEach { (armId, expected) ->
            val records = recorded[armId] ?: fail("recorded/$armId.jsonl is missing; the report quotes it")
            val report = replay(armId, records)
            val confusion = report.confusion(armId)
            assertEquals(expected.falseMet, confusion.falseMet, "$armId false met")
            assertEquals(expected.falseNotMet, confusion.falseNotMet, "$armId false not met")
            assertEquals(expected.missing, confusion.missing, "$armId missing")
            assertEquals(expected.fittable, report.isFittable(armId), "$armId fittable")
        }
    }

    @Test
    fun `renders the full report for the document`() {
        val arms = GoalMetArms.all.map { (id, client) -> DecisionArm(id, client, configuration) }
        val deterministic = runBlocking { DecisionBench(DecideBenchLive.QUESTION_ID).run(corpus, arms, repeats = 1) }
        val sections = mutableListOf(deterministic.render("Goal-met bench — deterministic arms (noul, 1 repeat)"))
        recorded.forEach { (armId, records) ->
            sections += replay(armId, records).render("Goal-met bench — `$armId` (replay of recorded/$armId.jsonl)")
        }
        val output = Paths.get("build/reports/decide")
        Files.createDirectories(output)
        Files.writeString(output.resolve("goal-met.md"), sections.joinToString("\n---\n\n"))
        assertTrue(Files.size(output.resolve("goal-met.md")) > 0)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    /** The counts `docs/ampr-422-goal-met-bench.md` quotes, per recorded arm. */
    private data class Headline(val falseMet: Int, val falseNotMet: Int, val missing: Int, val fittable: Boolean)

    private companion object {
        /**
         * From the live run of 2026-10-15 (three repeats, 288 cases, 432 met and 432 unmet calls per
         * arm). The Haiku arms are 864 missing: the shipped `ModelBackedDecisionClient` parser fails on
         * every reply, which the report states as the finding it is.
         */
        val HEADLINES: Map<String, Headline> = mapOf(
            "claude-sonnet-5.noul" to Headline(falseMet = 65, falseNotMet = 2, missing = 0, fittable = false),
            "claude-sonnet-5.choice" to Headline(falseMet = 47, falseNotMet = 10, missing = 0, fittable = false),
            "claude-haiku-4-5.noul" to Headline(falseMet = 0, falseNotMet = 432, missing = 864, fittable = false),
            "claude-haiku-4-5.choice" to Headline(falseMet = 0, falseNotMet = 432, missing = 864, fittable = false),
        )
    }
}
