package link.socket.ampere.agents.execution.verify

import kotlin.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A repository's `.ampere/verify.yml`, parsed: its definition of done as data
 * (schema v1, `docs/verify-manifest.md`).
 *
 * Built only by the parser, which refuses anything schema v1 does not describe, so a
 * value of this type has already passed every structural rule: ids are unique, every
 * `requires`/`needs` reference resolves to a declared precondition of the right
 * kind, every gate has exactly one of `run`/`steps`, and no command names more than
 * one task.
 *
 * @property requires Hard preconditions. One absent refuses the whole run.
 * @property optional Soft preconditions. One absent reports the gates that `need` it
 *   as not executed.
 * @property gates In listed order, which is the order they run in. Order never
 *   short-circuits: every gate runs regardless of every other gate's outcome.
 */
data class VerifyManifest(
    val version: Int,
    val requires: List<Precondition>,
    val optional: List<Precondition>,
    val gates: List<ManifestGate>,
) {
    /** Every declared precondition by id. Ids are unique across both lists. */
    val preconditions: Map<String, Precondition> by lazy { (requires + optional).associateBy { it.id } }

    companion object {
        /** The only schema version this build implements. */
        const val SUPPORTED_VERSION: Int = 1

        /** Where a repository keeps its manifest, relative to its root. */
        const val PATH: String = ".ampere/verify.yml"
    }
}

/**
 * One precondition: an id, exactly one predicate, and why the gates need it.
 *
 * A precondition must be cheap and unambiguous to check, which is why there are four
 * predicates and no more. Anything the build tool resolves and fails loudly about is
 * left to the build tool and surfaces as an infrastructure outcome.
 */
data class Precondition(
    val id: String,
    val predicate: PreconditionPredicate,
    val reason: String? = null,
)

/** What has to be true of the host for a [Precondition] to hold. */
sealed interface PreconditionPredicate {

    /** The manifest key this predicate is spelled with. */
    val key: String

    /** A JVM toolchain of [version] is resolvable. */
    data class Toolchain(val version: String) : PreconditionPredicate {
        override val key: String get() = "toolchain"
    }

    /** [path], relative to the repository root, exists. */
    data class File(val path: String) : PreconditionPredicate {
        override val key: String get() = "file"
    }

    /** [command] exits 0. */
    data class Command(val command: String) : PreconditionPredicate {
        override val key: String get() = "command"
    }

    /** At least one available simulator's name matches [glob]. */
    data class Simulator(val glob: String) : PreconditionPredicate {
        override val key: String get() = "simulator"
    }
}

/**
 * One gate: one command (or an irreducible sequence of them) and the metadata a runner
 * needs to execute it and classify what happened.
 *
 * @property steps The commands, in order. A `run:` gate is one unnamed step; a
 *   `steps:` gate stops at its first non-zero step and reports that step's id.
 * @property sequenced Whether the manifest declared `steps:` rather than `run:`.
 * @property fresh The verdict may not come from a cache. Applies to the last step,
 *   the one that produces the verdict.
 * @property timeout Wall clock for the whole gate. Exceeding it is infrastructure,
 *   never a failure.
 * @property retrySafe Re-running the gate from the current tree is sound — which is
 *   what lets the runner re-run a failure to see whether it reproduces.
 * @property parallelSafe Recorded, never acted on: v1 runs a repository's gates one
 *   at a time even where this is true (Gradle daemon and lock contention).
 */
data class ManifestGate(
    val id: String,
    val steps: List<GateStep>,
    val sequenced: Boolean,
    val fresh: Boolean,
    val timeout: Duration,
    val retrySafe: Boolean,
    val sideEffects: SideEffects,
    val parallelSafe: Boolean,
    val requires: List<String>,
    val needs: List<String>,
    val baseline: Baseline,
) {
    /** Every precondition id this gate depends on, hard first. */
    val dependsOn: List<String> get() = requires + needs
}

/**
 * One command of a gate.
 *
 * @property id Null for a `run:` gate's single command; a `steps:` entry always has one.
 * @property run Exactly one command, exactly as written. `${env.VAR}` is substituted at
 *   execution time.
 */
data class GateStep(
    val id: String?,
    val run: String,
)

/** What a gate touches besides its own process. */
@Serializable
enum class SideEffects {
    @SerialName("none")
    NONE,

    @SerialName("build-dir")
    BUILD_DIR,

    @SerialName("device")
    DEVICE,

    @SerialName("network")
    NETWORK,
    ;

    /** The manifest spelling. */
    val wire: String get() = name.lowercase().replace('_', '-')

    companion object {
        fun fromWire(value: String): SideEffects? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Where a green result for a gate's exact task is observable off this host.
 *
 * A `ci` gate that is red here has an off-host green to check before the agent is
 * charged; a `none` gate has no acquittal available anywhere. The v1 runner records
 * it in every result and escalation; it does not query a forge for the off-host
 * green (local-host only).
 */
@Serializable
enum class Baseline {
    @SerialName("ci")
    CI,

    @SerialName("release")
    RELEASE,

    @SerialName("none")
    NONE,
    ;

    /** The manifest spelling. */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): Baseline? = entries.firstOrNull { it.wire == value }
    }
}
