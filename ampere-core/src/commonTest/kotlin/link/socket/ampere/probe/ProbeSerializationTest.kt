package link.socket.ampere.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Verdicts and ProbeReports cross the wire (Oscilloscope listing, future bus
 * events). Like `CanonSerializationTest`, the discriminators are pinned here
 * as literal strings so a rename fails loudly instead of silently breaking
 * decoding of recorded reports.
 */
class ProbeSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private fun verdictSamples(): Map<String, Verdict> = mapOf(
        "verdict.holds" to Verdict.Holds(reason = "fits"),
        "verdict.warn" to Verdict.Warn(reason = "210 CFM filter on a 226 CFM fan"),
        "verdict.violated" to Verdict.Violated(reason = "duct undersized"),
        "verdict.undetermined" to Verdict.Undetermined(
            reason = "grille publishes no CFM",
            cause = UndeterminedCause.EVIDENCE_ABSENT,
        ),
    )

    @Test
    fun `every verdict round-trips through the sealed serializer`() {
        verdictSamples().forEach { (discriminator, verdict) ->
            val encoded = json.encodeToString(Verdict.serializer(), verdict)
            val decoded = json.decodeFromString(Verdict.serializer(), encoded)

            assertEquals(verdict, decoded, "round-trip changed $discriminator")
        }
    }

    @Test
    fun `every verdict writes its pinned discriminator`() {
        verdictSamples().forEach { (discriminator, verdict) ->
            val encoded = json.encodeToString(Verdict.serializer(), verdict)

            assertTrue(
                encoded.contains("\"type\":\"$discriminator\""),
                "expected discriminator $discriminator; got $encoded",
            )
        }
    }

    @Test
    fun `holds round-trips with a null reason`() {
        val encoded = json.encodeToString(Verdict.serializer(), Verdict.Holds())
        val decoded = json.decodeFromString(Verdict.serializer(), encoded)

        assertEquals(Verdict.Holds(reason = null), decoded)
    }

    @Test
    fun `probe report round-trips with every verdict shape`() {
        verdictSamples().forEach { (discriminator, verdict) ->
            val report = ProbeReport(
                probeId = ProbeId("probe-1"),
                subjectId = "subject-1",
                verdict = verdict,
            )

            val encoded = json.encodeToString(ProbeReport.serializer(), report)
            val decoded = json.decodeFromString(ProbeReport.serializer(), encoded)

            assertEquals(report, decoded, "round-trip changed report with $discriminator")
        }
    }
}
