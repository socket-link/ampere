package link.socket.ampere.cli.animation.demo

import kotlin.time.Duration

/**
 * A pre-defined demo scenario with a scripted event stream.
 *
 * @property name Unique name for CLI argument
 * @property description Human-readable description
 * @property events The mock event stream for this scenario
 */
sealed class DemoScenario(
    val name: String,
    val description: String,
    val events: List<MockEvent>
) {
    /** Total duration of this scenario */
    val totalDuration: Duration
        get() = events.maxOfOrNull { it.time } ?: Duration.ZERO

    /**
     * Release notes demo: Two agents collaborate to summarize commits.
     */
    object ReleaseNotes : DemoScenario(
        name = "release-notes",
        description = "Two agents collaborate to summarize commits and draft release notes",
        events = createReleaseNotesMockEvents()
    )

    /**
     * Code review demo: Three agents perform code review with handoffs.
     */
    object CodeReview : DemoScenario(
        name = "code-review",
        description = "Three agents perform code review with multiple handoffs",
        events = createCodeReviewMockEvents()
    )

    companion object {
        /** All available scenarios */
        val all: List<DemoScenario> = listOf(ReleaseNotes, CodeReview)

        /** Get scenario by name */
        fun byName(name: String): DemoScenario? = all.find {
            it.name.equals(name, ignoreCase = true)
        }

        /** Default scenario */
        val default: DemoScenario = ReleaseNotes

        /** List all scenario names */
        val names: List<String> = all.map { it.name }
    }
}

/**
 * Configuration for demo playback.
 */
data class DemoConfig(
    /** Playback speed multiplier (1.0 = normal) */
    val speed: Float = 1.0f,

    /** Whether to show the logo crystallization */
    val showLogo: Boolean = true,

    /** Whether to show substrate animation */
    val showSubstrate: Boolean = true,

    /** Whether to show particle effects */
    val showParticles: Boolean = true,

    /** Whether to use colored output */
    val useColor: Boolean = true,

    /** Whether to use Unicode glyphs */
    val useUnicode: Boolean = true,

    /** Target frame rate (frames per second) */
    val targetFps: Int = 20
) {
    /** Frame interval in milliseconds */
    val frameIntervalMs: Long = (1000L / targetFps)

    /** Adjusted frame interval accounting for speed */
    val adjustedFrameIntervalMs: Long = frameIntervalMs

    companion object {
        /** Fast config for quick preview */
        val fast = DemoConfig(speed = 2.0f)

        /** Slow config for detailed recording */
        val slow = DemoConfig(speed = 0.5f)

        /** Config for CI/testing */
        val ci = DemoConfig(
            speed = 4.0f,
            showLogo = false,
            showParticles = false
        )
    }
}
