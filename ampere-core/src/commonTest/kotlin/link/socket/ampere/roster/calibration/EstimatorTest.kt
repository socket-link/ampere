package link.socket.ampere.roster.calibration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import link.socket.ampere.canon.CanonId

/** AMPR-379 task 1 validation: the Estimator's Recall step reads the source and estimates scale. */
class EstimatorTest {

    private val baseline = listOf(
        WorkEstimate(CanonId("cut-duct"), EstimateCategory.PHYSICAL_WORK, 2.hours),
        WorkEstimate(CanonId("mount-fan"), EstimateCategory.ASSEMBLY, 90.minutes),
        WorkEstimate(CanonId("wait-sealant"), EstimateCategory.WAITING, 24.hours),
        WorkEstimate(CanonId("sand-frame"), EstimateCategory.PHYSICAL_WORK, 1.hours),
    )

    private class FakeSource(private val table: Map<EstimateCategory, Calibration>) : EstimateCalibrationSource {
        val reads = mutableListOf<EstimateCategory>()

        override suspend fun multiplier(category: EstimateCategory): Calibration {
            reads += category
            return table[category] ?: Calibration.NONE
        }
    }

    @Test
    fun `no calibration leaves every estimate as it was`() = runTest {
        val calibrated = Estimator.calibrate(baseline, NoCalibration)

        assertEquals(baseline.map { it.duration }, calibrated.map { it.duration })
        calibrated.forEach { assertEquals(Calibration.NONE, it.calibration) }
    }

    @Test
    fun `a source returning one point five scales physical work by one point five`() = runTest {
        val source = FakeSource(mapOf(EstimateCategory.PHYSICAL_WORK to Calibration(multiplier = 1.5, samples = 7)))

        val calibrated = Estimator.calibrate(baseline, source).associateBy { it.itemId.value }

        assertEquals(3.hours, calibrated.getValue("cut-duct").duration)
        assertEquals(90.minutes, calibrated.getValue("sand-frame").duration)
        assertEquals(90.minutes, calibrated.getValue("mount-fan").duration, "assembly is uncalibrated")
        assertEquals(24.hours, calibrated.getValue("wait-sealant").duration, "waiting is never shortened")
        assertEquals(7, calibrated.getValue("cut-duct").calibration.samples)
    }

    @Test
    fun `the source is read once per category`() = runTest {
        val source = FakeSource(emptyMap())

        Estimator.calibrate(baseline, source)

        assertEquals(
            listOf(EstimateCategory.PHYSICAL_WORK, EstimateCategory.ASSEMBLY, EstimateCategory.WAITING),
            source.reads,
        )
    }

    @Test
    fun `estimates and categories round-trip with snake case wire names`() {
        val json = Json {
            encodeDefaults = true
            classDiscriminator = "type"
        }
        val estimate = CalibratedEstimate(baseline.first(), Calibration(1.5, 7))

        val encoded = json.encodeToString(CalibratedEstimate.serializer(), estimate)

        assertEquals(estimate, json.decodeFromString(CalibratedEstimate.serializer(), encoded))
        assertEquals(
            "\"physical_work\"",
            json.encodeToString(EstimateCategory.serializer(), EstimateCategory.PHYSICAL_WORK),
        )
        assertEquals("\"waiting\"", json.encodeToString(EstimateCategory.serializer(), EstimateCategory.WAITING))
    }
}
