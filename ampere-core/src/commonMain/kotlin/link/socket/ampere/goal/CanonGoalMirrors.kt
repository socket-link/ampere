package link.socket.ampere.goal

import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonMilestone
import link.socket.ampere.canon.CanonProject
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.field.Environment
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Rule
import link.socket.ampere.field.observe.canonEnvironmentRef
import link.socket.ampere.field.observe.environmentRef

/**
 * The mapper between perceived tracker work and the Goal branch (AMPR-419, F8). Pure: it names
 * refs and builds rules, and writes nothing — [GoalBranch] records what it produces.
 *
 * A `CanonWorkItem`, `CanonProject` or `CanonMilestone` is the tracker's view of some work. It is
 * Environment the agent perceived, and it links to the agent's own [Goal] as the `from` end of a
 * `Mirrors` edge: the tracker's view mirrors the goal and never stands in for it. Every other
 * canon type has nothing to mirror — an email is not a view of a goal — so [mirrorRef] is `null`
 * for it.
 *
 * A canon entity's end of the edge is its [environmentRef] — the same Link-qualified
 * `Ref<Environment>` its Observations are about (AMPR-418) — so a mirror and an Observation of
 * the same item name one subject, and two Links' items with the same `CanonId` stay two.
 */
object CanonGoalMirrors {

    /**
     * The ref [entity] mirrors a goal under, or `null` when its type is not tracker work. Exactly
     * the three work types: the work item, the project and the milestone.
     */
    fun mirrorRef(entity: CanonEntity): Ref<Environment>? = when (entity) {
        is CanonWorkItem, is CanonProject, is CanonMilestone -> entity.environmentRef
        else -> null
    }

    /**
     * The ref of the work item [dependency] names in [item]'s `dependsOn`. A dependency is a
     * same-Link id, so it is qualified by [item]'s own Link.
     */
    fun dependencyRef(item: CanonWorkItem, dependency: CanonId): Ref<Environment> =
        canonEnvironmentRef(item.provenance.sourceHandle.linkId, dependency)

    /**
     * [item]'s `dependsOn` as a [Rule.Dependency] on the goal [item] mirrors.
     *
     * A canon dependency names another *work item*; a rule names *goals*. [goalsMirroredBy]
     * crosses that gap — for each dependency's [dependencyRef], the goals that work item mirrors.
     * A dependency that mirrors no goal cannot be stated as a rule over goals, so it is returned
     * in [DependencyMapping.unresolved] rather than dropped: it is still a dependency, the Field
     * just has no goal to hang it on yet.
     *
     * The rule is produced, not evaluated. Deciding whether an Action honours it is the W3
     * gate's job; nothing here reads it.
     */
    fun dependency(
        item: CanonWorkItem,
        goalsMirroredBy: (Ref<Environment>) -> Set<Ref<Goal>>,
    ): DependencyMapping {
        val resolved = linkedSetOf<Ref<Goal>>()
        val unresolved = mutableListOf<CanonId>()
        for (dependency in item.dependsOn.distinct()) {
            val goals = goalsMirroredBy(dependencyRef(item, dependency))
            if (goals.isEmpty()) unresolved += dependency else resolved += goals
        }
        return DependencyMapping(
            rule = if (resolved.isEmpty()) null else Rule.Dependency(dependsOn = resolved),
            unresolved = unresolved,
        )
    }
}

/**
 * What a work item's `dependsOn` became.
 *
 * @property rule The dependencies that mirror goals, as one [Rule.Dependency]; `null` when none do
 *   (including when the item depends on nothing).
 * @property unresolved The dependency ids that mirror no goal, in `dependsOn` order.
 */
data class DependencyMapping(
    val rule: Rule.Dependency?,
    val unresolved: List<CanonId>,
)
