package link.socket.ampere.probe

import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkGraph

/**
 * Structural check over a [CanonWorkGraph]: every `dependsOn` edge resolves to
 * an item in the graph (referential integrity), and the edges form a DAG
 * (acyclicity). Both are always decidable from the graph alone, so this Probe
 * never returns [Verdict.Warn] or [Verdict.Undetermined].
 *
 * Checks run in order and the first failure is the verdict:
 *
 * 1. `Violated(reason = "dangling dependsOn: <itemId> -> <missingId>")` for
 *    the first edge, in item order, whose target is not an item of the graph.
 * 2. `Violated(reason = "cycle: A -> B -> C -> A")` for the first cycle found,
 *    with the closing edge repeated so the path reads as a loop.
 * 3. [Verdict.Holds] otherwise — including for a graph with no items.
 *
 * Timing invariants (offset non-inversion, recurrence-after-dependency) are
 * deliberately not here: Socket decision D17 put recurrence on
 * `CanonReminder`, and the Task↔Reminder mapping is held caller-side, so
 * timing checks live there in v1.
 */
class SequenceProbe(
    override val id: ProbeId = ProbeId(ID),
) : Probe<CanonWorkGraph> {

    override suspend fun evaluate(subject: CanonWorkGraph): Verdict {
        val edges: Map<CanonId, List<CanonId>> = subject.items.associate { it.canonId to it.dependsOn }

        for (item in subject.items) {
            val missing = item.dependsOn.firstOrNull { it !in edges } ?: continue
            return Verdict.Violated(reason = "dangling dependsOn: ${item.canonId.value} -> ${missing.value}")
        }

        val cycle = firstCycle(edges) ?: return Verdict.Holds()
        return Verdict.Violated(reason = "cycle: ${cycle.joinToString(" -> ") { it.value }}")
    }

    /**
     * Iterative DFS over [edges]. Returns the first back-edge as a closed path
     * (`A, B, C, A`), or null when the graph is acyclic. Iterative rather than
     * recursive so a long dependency chain cannot overflow the stack on a
     * Native target.
     */
    private fun firstCycle(edges: Map<CanonId, List<CanonId>>): List<CanonId>? {
        val done = mutableSetOf<CanonId>()
        val path = ArrayList<CanonId>()
        val onPath = mutableSetOf<CanonId>()

        for (root in edges.keys) {
            if (root in done) continue

            val cursors = ArrayDeque<Iterator<CanonId>>()
            path += root
            onPath += root
            cursors.addLast(edges.getValue(root).iterator())

            while (cursors.isNotEmpty()) {
                val cursor = cursors.last()
                if (!cursor.hasNext()) {
                    cursors.removeLast()
                    val finished = path.removeAt(path.lastIndex)
                    onPath -= finished
                    done += finished
                    continue
                }

                val next = cursor.next()
                when {
                    next in onPath -> return path.subList(path.indexOf(next), path.size) + next
                    next in done -> Unit
                    else -> {
                        path += next
                        onPath += next
                        // A dangling target has no entry; treat it as a leaf. Referential
                        // integrity is reported separately, before this runs.
                        cursors.addLast((edges[next] ?: emptyList()).iterator())
                    }
                }
            }
        }

        return null
    }

    companion object {
        /** The [ProbeId] this Probe registers under by default. */
        const val ID: String = "ampere.sequence"
    }
}
