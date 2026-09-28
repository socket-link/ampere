package link.socket.ampere.agents.domain.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which stage of delivery a unit of work belongs to: investigating, or
 * changing code (AMPR-369).
 *
 * **This is not [link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase].**
 * `CognitivePhase` (and `PhaseSparkConfig`, which is keyed by it) names a step
 * of the PROPEL loop *inside a single agent turn* — Perceive, Plan, Execute and
 * so on. Every unit of work passes through all of those steps whatever it is
 * for, so a cognitive phase says nothing about what the work may do. A
 * `WorkPhase` is a property of the work itself and does not change while an
 * agent cycles through its cognitive phases on it. It is equally unrelated to
 * [link.socket.ampere.domain.arc.ArcPhase], which names a stage of one Arc
 * run. The type is called `WorkPhase` rather than anything ending in a bare
 * `Phase` so the three cannot be mistaken for one another at a call site.
 *
 * The value is load-bearing, not descriptive: [permitsWrites] is what a
 * dispatcher reads to decide whether an unattended run may write at all. For
 * that reason a phase is fixed when the work is created
 * ([link.socket.ampere.agents.domain.event.Event.TaskCreated],
 * [link.socket.ampere.agents.domain.event.TaskEvent.SubtaskCreated]) and no
 * later lifecycle event can change it — starting a task cannot promote it from
 * read-only to writing.
 *
 * Work that carries no phase is *unclassified*, which is every unit of work
 * created before this type existed. A consumer gating writes on the phase must
 * treat unclassified as not permitted (`phase?.permitsWrites == true`) rather
 * than defaulting to the more permissive value.
 *
 * @property permitsWrites Whether work in this phase may modify the workspace.
 */
@Serializable
enum class WorkPhase(val permitsWrites: Boolean) {

    /** Read-only investigation: the work reads the codebase and reports, and must not write to it. */
    @SerialName("recon")
    RECON(permitsWrites = false),

    /** Implementation: the work writes code. */
    @SerialName("implementation")
    IMPLEMENTATION(permitsWrites = true),
}
