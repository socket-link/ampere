package link.socket.ampere.eval.suite

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.eval.trace.Trace

/**
 * Reads and writes the suite's committed golden traces (AMPR-187 tasks 5.2 and 5.5).
 *
 * ### Canonical form
 *
 * A recorded trace is full of per-run values — event ids, an Arc run id, wall-clock stamps — that
 * are not behavior and differ between two runs of identical code. Committed as-is, every
 * re-record would produce a diff and no reader could tell an incidental one from a real change.
 *
 * So a trace is *canonicalized* before it is written: every field
 * `TraceConformanceMeter.DEFAULT_VOLATILE_FIELDS` names is replaced with a stable placeholder of
 * the same JSON kind, at any depth, and so are the trace's own id, run id and `createdAt`. The
 * result stays a valid, decodable [Trace] — ids are still strings, instants are still ISO-8601 —
 * and re-recording an unchanged Arc rewrites the file byte-for-byte identically.
 *
 * That is what makes `git diff --exit-code` after a re-record the suite's sharpest tool: an empty
 * diff *is* the proof that replaying a golden trace reproduces its recorded outputs exactly.
 *
 * The placeholder set is deliberately the meter's volatile set, and
 * [AmpereEvalSuiteTest] asserts the two cannot drift apart — a field canonicalized here but
 * compared there would make the gate fail on every re-record.
 */
internal object GoldenTraces {

    /** Where committed golden traces live, relative to the module. */
    const val RESOURCE_DIR: String = "golden"

    /** The system property [GoldenTraceRecorderTest] takes its output directory from. */
    const val GOLDEN_DIR_PROPERTY: String = "ampere.eval.goldenDir"

    /** Instant placeholder. ISO-8601 so a canonicalized payload still decodes as its `Event`. */
    private const val EPOCH_ISO: String = "1970-01-01T00:00:00Z"

    private val json: Json = Json(DEFAULT_JSON) { prettyPrint = true }

    /** Fields replaced with a stable *identifier*. */
    private val ID_FIELDS: Set<String> = setOf("eventId", "runId", "arcRunId")

    /** Fields replaced with a stable *instant*. */
    private val TIME_FIELDS: Set<String> = setOf(
        "timestamp",
        "recordedAt",
        "createdAt",
        "executionStartTimestamp",
        "executionEndTimestamp",
    )

    /** Every field canonicalization touches — the same set conformance ignores. */
    val CANONICALIZED_FIELDS: Set<String> = ID_FIELDS + TIME_FIELDS

    /** The committed golden trace for [probeId], read from the test resources. */
    fun load(probeId: String): Trace {
        val resource = "/$RESOURCE_DIR/$probeId.json"
        val stream = requireNotNull(GoldenTraces::class.java.getResourceAsStream(resource)) {
            "Missing golden trace resource '$resource'. Record it with " +
                "`./gradlew :ampere-eval:recordGoldenTraces`."
        }
        return stream.use { json.decodeFromString(Trace.serializer(), it.readBytes().decodeToString()) }
    }

    /** Every probe's golden trace, keyed by probe id. */
    fun loadAll(): Map<String, Trace> = AmpereEvalSuite.probes.associate { it.id to load(it.id) }

    /** [trace] in canonical form, attributed to [probeId]. */
    fun canonicalize(probeId: String, trace: Trace): Trace = trace.copy(
        id = "golden-$probeId",
        runId = "golden-run-$probeId",
        createdAt = 0L,
        events = trace.events.mapIndexed { index, event ->
            event.copy(
                index = index,
                timestamp = 0L,
                payload = event.payload.canonicalized(probeId, index),
            )
        },
    )

    /**
     * Writes [trace] as `<probeId>.json` under [directory], creating it if needed. Ends in a
     * newline so the file is a well-formed text file and a diff of the last line reads normally.
     */
    fun write(directory: Path, probeId: String, trace: Trace) {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("$probeId.json"),
            json.encodeToString(Trace.serializer(), trace) + "\n",
        )
    }

    /**
     * The directory [GoldenTraceRecorderTest] writes to, from [GOLDEN_DIR_PROPERTY].
     *
     * Absent unless the `recordGoldenTraces` task set it, which is what keeps an ordinary
     * `jvmTest` run from rewriting the committed traces it is supposed to be checking against.
     */
    fun outputDirectory(): Path? = System.getProperty(GOLDEN_DIR_PROPERTY)?.let { Paths.get(it) }

    private fun JsonElement.canonicalized(probeId: String, eventIndex: Int): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            mapValues { (key, value) ->
                when {
                    key in ID_FIELDS -> JsonPrimitive(placeholderId(key, probeId, eventIndex))
                    key in TIME_FIELDS -> canonicalTime(value)
                    else -> value.canonicalized(probeId, eventIndex)
                }
            },
        )

        is JsonArray -> JsonArray(map { it.canonicalized(probeId, eventIndex) })
        else -> this
    }

    private fun placeholderId(key: String, probeId: String, eventIndex: Int): String = when (key) {
        "eventId" -> "$probeId-event-$eventIndex"
        "arcRunId" -> "golden-arc-run-$probeId"
        else -> "golden-run-$probeId"
    }

    /**
     * An instant field's placeholder, matching the kind the recording had: epoch milliseconds stay
     * a number, an ISO-8601 string stays a string. Anything else is left alone rather than
     * guessed at.
     */
    private fun canonicalTime(value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive ?: return value
        return when {
            primitive.isString -> JsonPrimitive(EPOCH_ISO)
            primitive.doubleOrNull != null -> JsonPrimitive(0)
            else -> primitive
        }
    }
}
