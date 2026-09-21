package link.socket.ampere.dsl.events

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.Event

/**
 * Boundary test for the [TeamEvent] view-layer rule (AMPR-334, F-register row F5).
 *
 * `TeamEvent` is a projection of [Event] for the `AgentTeam` DSL. It is never
 * persisted and never an input to the Field fold. This test fails if either of
 * the two rules that keep it a projection regresses:
 *
 * 1. A `TeamEvent` is not an `Event`. If someone makes `TeamEvent` extend `Event`,
 *    the fold would start seeing DSL projections as world-state input.
 * 2. No production code in `commonMain` outside `link/socket/ampere/dsl/` refers
 *    to `TeamEvent`. Consumers of the projection live in the DSL; the one
 *    sanctioned exception is the public `AmpereConfig.onEscalation` callback,
 *    which names the `Escalated` subtype and is allow-listed below.
 *
 * jvmTest because rule 2 walks the source tree with `java.io.File`; the
 * hierarchy it checks is declared in `commonMain`, so covering it once on one
 * target is enough.
 */
class TeamEventBoundaryTest {

    @Test
    fun `a TeamEvent is not an Event`() {
        val sample: TeamEvent = GoalSet(goal = "sample", timestamp = Clock.System.now())
        assertFalse(Event::class.isInstance(sample), "TeamEvent must never be assignable to Event")
    }

    @Test
    fun `TeamEvent is referenced only under dsl`() {
        val offenders = commonMainSources()
            .filterNot { it.isUnderDsl() }
            .flatMap { file ->
                val relative = file.relativeTo(commonMainRoot()).path
                file.readLines().mapIndexedNotNull { index, line ->
                    if (TEAM_EVENT_TOKEN.containsMatchIn(line)) "$relative:${index + 1}: $line" else null
                }
            }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "TeamEvent is a DSL view layer and must not be referenced outside " +
                "link/socket/ampere/dsl/. Offending lines:\n${offenders.joinToString("\n")}",
        )
    }

    @Test
    fun `dsl events are imported outside dsl only by allow-listed files`() {
        val offenders = commonMainSources()
            .filterNot { it.isUnderDsl() }
            .filter { file -> file.readLines().any { DSL_EVENTS_IMPORT.containsMatchIn(it) } }
            .map { it.relativeTo(commonMainRoot()).path }
            .filterNot { it in SANCTIONED_DSL_EVENTS_CONSUMERS }
            .sorted()

        assertTrue(
            offenders.isEmpty(),
            "New consumers of link.socket.ampere.dsl.events outside dsl/. Either move the " +
                "code into the DSL or, if it is public API like AmpereConfig.onEscalation, " +
                "add it to SANCTIONED_DSL_EVENTS_CONSUMERS with a reason:\n${offenders.joinToString("\n")}",
        )
    }

    private fun commonMainSources(): List<File> =
        commonMainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun File.isUnderDsl(): Boolean =
        relativeTo(commonMainRoot()).invariantSeparatorsPath.startsWith(DSL_PREFIX)

    /**
     * Gradle runs jvmTest with the module directory as the working directory. Walk
     * up from there so the test also works when launched from the repo root or an
     * IDE with a different working directory.
     */
    private fun commonMainRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, COMMON_MAIN)
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "ampere-core/$COMMON_MAIN")
            if (nested.isDirectory) return nested
            dir = dir.parentFile
        }
        error("Could not locate $COMMON_MAIN from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val COMMON_MAIN = "src/commonMain/kotlin"
        const val DSL_PREFIX = "link/socket/ampere/dsl/"

        val TEAM_EVENT_TOKEN = Regex("\\bTeamEvent\\b")
        val DSL_EVENTS_IMPORT = Regex("^\\s*import\\s+link\\.socket\\.ampere\\.dsl\\.events\\.")

        /**
         * Files outside dsl/ that may import from `link.socket.ampere.dsl.events`.
         * Each entry needs a reason; adding one is a design decision, not a fix.
         */
        val SANCTIONED_DSL_EVENTS_CONSUMERS = setOf(
            // Public API: `onEscalation: ((Escalated) -> Unit)?` is the user-facing
            // escalation callback; DefaultAmpereInstance (jvmMain) feeds it via
            // TeamEventAdapter.adapt, so the value it receives is still a projection.
            "link/socket/ampere/api/AmpereConfig.kt",
        )
    }
}
