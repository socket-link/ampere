package link.socket.ampere.demo

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Timing configuration for demo execution.
 *
 * Controls delays at each phase to achieve target demo durations:
 * - FAST: ~45s (quick GIF recording)
 * - DEFAULT: ~85s (default viewing experience)
 * - DETAILED: ~120s (conference presentations with extra pauses)
 */
data class DemoTiming(
    val name: String,
    val targetDurationSeconds: Int,
    val initializationDelay: Duration,
    val perceiveDelay: Duration,
    val perceiveResultPause: Duration,
    val escalationDelay: Duration,
    val autoRespondCountdownInterval: Duration,
    val planDelay: Duration,
    val planResultPause: Duration,
    val executeDelay: Duration,
    val executeProgressInterval: Duration,
    val learnDelay: Duration,
    val completionGracePeriod: Duration,
    val renderInterval: Duration,
    val inputPollingInterval: Duration,
    // Multi-agent handoff timing
    val coordinatorPerceiveDelay: Duration,
    val coordinatorPlanDelay: Duration,
    val delegationDisplayDelay: Duration,
) {
    companion object {
        /**
         * Fast timing profile (~45 seconds total).
         *
         * Designed for quick GIF recording and CI verification.
         * Minimal pauses, fast transitions.
         */
        val FAST = DemoTiming(
            name = "fast",
            targetDurationSeconds = 45,
            initializationDelay = 200.milliseconds,
            perceiveDelay = 500.milliseconds,
            perceiveResultPause = 300.milliseconds,
            escalationDelay = 200.milliseconds,
            autoRespondCountdownInterval = 500.milliseconds,
            planDelay = 500.milliseconds,
            planResultPause = 300.milliseconds,
            executeDelay = 500.milliseconds,
            executeProgressInterval = 200.milliseconds,
            learnDelay = 300.milliseconds,
            completionGracePeriod = 3.seconds,
            renderInterval = 200.milliseconds,
            inputPollingInterval = 50.milliseconds,
            coordinatorPerceiveDelay = 1.seconds,
            coordinatorPlanDelay = 1.seconds,
            delegationDisplayDelay = 2.seconds,
        )

        /**
         * Default timing profile (~85 seconds total).
         *
         * Balanced experience for general viewing.
         * Moderate pauses allow viewers to follow along.
         */
        val DEFAULT = DemoTiming(
            name = "default",
            targetDurationSeconds = 85,
            initializationDelay = 500.milliseconds,
            perceiveDelay = 1.seconds,
            perceiveResultPause = 1.seconds,
            escalationDelay = 500.milliseconds,
            autoRespondCountdownInterval = 1.seconds,
            planDelay = 1.seconds,
            planResultPause = 1.seconds,
            executeDelay = 1.seconds,
            executeProgressInterval = 500.milliseconds,
            learnDelay = 1.seconds,
            completionGracePeriod = 10.seconds,
            renderInterval = 250.milliseconds,
            inputPollingInterval = 50.milliseconds,
            coordinatorPerceiveDelay = 2.seconds,
            coordinatorPlanDelay = 3.seconds,
            delegationDisplayDelay = 3.seconds,
        )

        /**
         * Detailed timing profile (~120 seconds total).
         *
         * Designed for conference presentations.
         * Longer pauses for explanation and audience comprehension.
         */
        val DETAILED = DemoTiming(
            name = "detailed",
            targetDurationSeconds = 120,
            initializationDelay = 1.seconds,
            perceiveDelay = 2.seconds,
            perceiveResultPause = 2.seconds,
            escalationDelay = 1.seconds,
            autoRespondCountdownInterval = 1500.milliseconds,
            planDelay = 2.seconds,
            planResultPause = 2.seconds,
            executeDelay = 2.seconds,
            executeProgressInterval = 1.seconds,
            learnDelay = 2.seconds,
            completionGracePeriod = 15.seconds,
            renderInterval = 250.milliseconds,
            inputPollingInterval = 50.milliseconds,
            coordinatorPerceiveDelay = 4.seconds,
            coordinatorPlanDelay = 5.seconds,
            delegationDisplayDelay = 5.seconds,
        )

        /**
         * Get a timing profile by name.
         */
        fun byName(name: String): DemoTiming = when (name.lowercase()) {
            "fast" -> FAST
            "default" -> DEFAULT
            "detailed" -> DETAILED
            else -> DEFAULT
        }
    }
}
