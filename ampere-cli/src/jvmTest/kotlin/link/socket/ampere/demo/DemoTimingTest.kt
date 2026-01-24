package link.socket.ampere.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for DemoTiming profiles.
 *
 * Verifies timing profiles are well-structured and within expected bounds.
 */
class DemoTimingTest {

    // =========================================================================
    // Profile Structure Tests
    // =========================================================================

    @Test
    fun `FAST profile has correct name`() {
        assertEquals("fast", DemoTiming.FAST.name)
    }

    @Test
    fun `DEFAULT profile has correct name`() {
        assertEquals("default", DemoTiming.DEFAULT.name)
    }

    @Test
    fun `DETAILED profile has correct name`() {
        assertEquals("detailed", DemoTiming.DETAILED.name)
    }

    // =========================================================================
    // Profile Ordering Tests (FAST < DEFAULT < DETAILED)
    // =========================================================================

    @Test
    fun `FAST target duration is less than DEFAULT`() {
        assertTrue(
            DemoTiming.FAST.targetDurationSeconds < DemoTiming.DEFAULT.targetDurationSeconds,
            "FAST (${DemoTiming.FAST.targetDurationSeconds}s) should be less than DEFAULT (${DemoTiming.DEFAULT.targetDurationSeconds}s)"
        )
    }

    @Test
    fun `DEFAULT target duration is less than DETAILED`() {
        assertTrue(
            DemoTiming.DEFAULT.targetDurationSeconds < DemoTiming.DETAILED.targetDurationSeconds,
            "DEFAULT (${DemoTiming.DEFAULT.targetDurationSeconds}s) should be less than DETAILED (${DemoTiming.DETAILED.targetDurationSeconds}s)"
        )
    }

    @Test
    fun `FAST completion grace period is less than DEFAULT`() {
        assertTrue(
            DemoTiming.FAST.completionGracePeriod < DemoTiming.DEFAULT.completionGracePeriod,
            "FAST grace period should be less than DEFAULT grace period"
        )
    }

    @Test
    fun `DEFAULT completion grace period is less than DETAILED`() {
        assertTrue(
            DemoTiming.DEFAULT.completionGracePeriod < DemoTiming.DETAILED.completionGracePeriod,
            "DEFAULT grace period should be less than DETAILED grace period"
        )
    }

    // =========================================================================
    // Maximum Bounds Tests
    // =========================================================================

    @Test
    fun `FAST target duration is under 60 seconds`() {
        assertTrue(
            DemoTiming.FAST.targetDurationSeconds < 60,
            "FAST profile should complete in under 60 seconds"
        )
    }

    @Test
    fun `DEFAULT target duration is under 120 seconds`() {
        assertTrue(
            DemoTiming.DEFAULT.targetDurationSeconds < 120,
            "DEFAULT profile should complete in under 120 seconds"
        )
    }

    @Test
    fun `DETAILED target duration is under 180 seconds`() {
        assertTrue(
            DemoTiming.DETAILED.targetDurationSeconds < 180,
            "DETAILED profile should complete in under 180 seconds"
        )
    }

    @Test
    fun `no phase delay exceeds 5 seconds in any profile`() {
        val profiles = listOf(DemoTiming.FAST, DemoTiming.DEFAULT, DemoTiming.DETAILED)
        val maxDelay = 5.seconds

        profiles.forEach { profile ->
            assertTrue(
                profile.initializationDelay <= maxDelay,
                "${profile.name}: initializationDelay exceeds $maxDelay"
            )
            assertTrue(
                profile.perceiveDelay <= maxDelay,
                "${profile.name}: perceiveDelay exceeds $maxDelay"
            )
            assertTrue(
                profile.perceiveResultPause <= maxDelay,
                "${profile.name}: perceiveResultPause exceeds $maxDelay"
            )
            assertTrue(
                profile.escalationDelay <= maxDelay,
                "${profile.name}: escalationDelay exceeds $maxDelay"
            )
            assertTrue(
                profile.planDelay <= maxDelay,
                "${profile.name}: planDelay exceeds $maxDelay"
            )
            assertTrue(
                profile.planResultPause <= maxDelay,
                "${profile.name}: planResultPause exceeds $maxDelay"
            )
            assertTrue(
                profile.executeDelay <= maxDelay,
                "${profile.name}: executeDelay exceeds $maxDelay"
            )
            assertTrue(
                profile.learnDelay <= maxDelay,
                "${profile.name}: learnDelay exceeds $maxDelay"
            )
        }
    }

    // =========================================================================
    // Minimum Bounds Tests
    // =========================================================================

    @Test
    fun `input polling interval is at least 10ms`() {
        val minPolling = 10.milliseconds
        listOf(DemoTiming.FAST, DemoTiming.DEFAULT, DemoTiming.DETAILED).forEach { profile ->
            assertTrue(
                profile.inputPollingInterval >= minPolling,
                "${profile.name}: inputPollingInterval should be at least $minPolling for responsive UI"
            )
        }
    }

    @Test
    fun `render interval is at least 100ms`() {
        val minRender = 100.milliseconds
        listOf(DemoTiming.FAST, DemoTiming.DEFAULT, DemoTiming.DETAILED).forEach { profile ->
            assertTrue(
                profile.renderInterval >= minRender,
                "${profile.name}: renderInterval should be at least $minRender for stable rendering"
            )
        }
    }

    @Test
    fun `auto-respond countdown interval is at least 100ms`() {
        val minCountdown = 100.milliseconds
        listOf(DemoTiming.FAST, DemoTiming.DEFAULT, DemoTiming.DETAILED).forEach { profile ->
            assertTrue(
                profile.autoRespondCountdownInterval >= minCountdown,
                "${profile.name}: autoRespondCountdownInterval should be at least $minCountdown"
            )
        }
    }

    // =========================================================================
    // Profile Lookup Tests
    // =========================================================================

    @Test
    fun `byName returns FAST for 'fast'`() {
        assertEquals(DemoTiming.FAST, DemoTiming.byName("fast"))
    }

    @Test
    fun `byName returns DEFAULT for 'default'`() {
        assertEquals(DemoTiming.DEFAULT, DemoTiming.byName("default"))
    }

    @Test
    fun `byName returns DETAILED for 'detailed'`() {
        assertEquals(DemoTiming.DETAILED, DemoTiming.byName("detailed"))
    }

    @Test
    fun `byName is case insensitive`() {
        assertEquals(DemoTiming.FAST, DemoTiming.byName("FAST"))
        assertEquals(DemoTiming.FAST, DemoTiming.byName("Fast"))
        assertEquals(DemoTiming.DEFAULT, DemoTiming.byName("DEFAULT"))
        assertEquals(DemoTiming.DETAILED, DemoTiming.byName("DETAILED"))
    }

    @Test
    fun `byName returns DEFAULT for unknown profile`() {
        assertEquals(DemoTiming.DEFAULT, DemoTiming.byName("unknown"))
        assertEquals(DemoTiming.DEFAULT, DemoTiming.byName(""))
        assertEquals(DemoTiming.DEFAULT, DemoTiming.byName("custom"))
    }

    // =========================================================================
    // Target Duration Verification
    // =========================================================================

    @Test
    fun `FAST target is approximately 45 seconds`() {
        val expected = 45
        val tolerance = 15 // +/- 15 seconds
        val actual = DemoTiming.FAST.targetDurationSeconds
        assertTrue(
            actual in (expected - tolerance)..(expected + tolerance),
            "FAST target ($actual) should be within $tolerance seconds of $expected"
        )
    }

    @Test
    fun `DEFAULT target is approximately 85 seconds`() {
        val expected = 85
        val tolerance = 20 // +/- 20 seconds
        val actual = DemoTiming.DEFAULT.targetDurationSeconds
        assertTrue(
            actual in (expected - tolerance)..(expected + tolerance),
            "DEFAULT target ($actual) should be within $tolerance seconds of $expected"
        )
    }

    @Test
    fun `DETAILED target is approximately 120 seconds`() {
        val expected = 120
        val tolerance = 30 // +/- 30 seconds
        val actual = DemoTiming.DETAILED.targetDurationSeconds
        assertTrue(
            actual in (expected - tolerance)..(expected + tolerance),
            "DETAILED target ($actual) should be within $tolerance seconds of $expected"
        )
    }

    // =========================================================================
    // Consistency Tests
    // =========================================================================

    @Test
    fun `all profiles have same input polling interval`() {
        // Input polling should be consistent for responsive UI
        assertEquals(
            DemoTiming.FAST.inputPollingInterval,
            DemoTiming.DEFAULT.inputPollingInterval,
            "FAST and DEFAULT should have same input polling interval"
        )
        assertEquals(
            DemoTiming.DEFAULT.inputPollingInterval,
            DemoTiming.DETAILED.inputPollingInterval,
            "DEFAULT and DETAILED should have same input polling interval"
        )
    }

    @Test
    fun `completion grace period scales appropriately`() {
        // FAST should have short grace, DETAILED should have longer
        assertTrue(
            DemoTiming.FAST.completionGracePeriod <= 5.seconds,
            "FAST grace period should be at most 5 seconds"
        )
        assertTrue(
            DemoTiming.DEFAULT.completionGracePeriod >= 5.seconds,
            "DEFAULT grace period should be at least 5 seconds"
        )
        assertTrue(
            DemoTiming.DETAILED.completionGracePeriod >= 10.seconds,
            "DETAILED grace period should be at least 10 seconds"
        )
    }
}
