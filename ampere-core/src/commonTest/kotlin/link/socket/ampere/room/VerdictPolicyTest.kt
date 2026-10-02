package link.socket.ampere.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.SequenceProbe
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.roster.BlueprintRoster

/**
 * AMPR-379 tasks 2 and 3, the pure half: which verdict opens a thread, who it is
 * assigned to, and when the human is asked.
 */
class VerdictPolicyTest {

    private val policy = VerdictPolicy(BlueprintRoster)
    private val partFit = ProbeId("blueprint.part-fit")
    private val sequence = ProbeId(SequenceProbe.ID)

    private fun verdict(subjectId: String, verdict: ProbeVerdict, probeId: ProbeId = partFit) =
        ProbeEvent.VerdictReached(
            eventId = "evt-$subjectId",
            eventSource = EventSource.Agent("inspector"),
            timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000),
            probeId = probeId,
            subjectId = subjectId,
            verdict = verdict,
        )

    private val violated = ThreadSubject.Verdict("duct", VerdictKind.VIOLATED)

    @Test
    fun `a violated part verdict opens a thread for the scout and does not ask the human`() {
        val actions = policy.decide(verdict("duct", ProbeVerdict.Violated("duct undersized")), open = emptyList())

        assertEquals(2, actions.size)
        val open = assertIs<VerdictPolicy.Action.OpenThread>(actions[0])
        assertEquals(violated, open.subject)
        assertEquals(BlueprintRoster.scout.id, open.assignedTo)
        assertIs<VerdictPolicy.Action.PostCard>(actions[1])
        assertTrue(actions.none { it is VerdictPolicy.Action.Escalate })
    }

    @Test
    fun `a violated sequence verdict is assigned to the planner`() {
        val actions = policy.decide(
            verdict("vent-42", ProbeVerdict.Violated("cycle: a -> b -> a"), probeId = sequence),
            open = emptyList(),
        )

        assertEquals(BlueprintRoster.planner.id, assertIs<VerdictPolicy.Action.OpenThread>(actions[0]).assignedTo)
    }

    @Test
    fun `an undetermined verdict opens a thread and asks the human at once`() {
        val actions = policy.decide(
            verdict("grille", ProbeVerdict.Undetermined("grille publishes no CFM", UndeterminedCause.EVIDENCE_ABSENT)),
            open = emptyList(),
        )

        assertEquals(
            listOf(
                VerdictPolicy.Action.OpenThread::class,
                VerdictPolicy.Action.PostCard::class,
                VerdictPolicy.Action.Escalate::class,
            ),
            actions.map { it::class },
        )
        val escalate = assertIs<VerdictPolicy.Action.Escalate>(actions[2])
        assertEquals(ThreadSubject.Verdict("grille", VerdictKind.UNDETERMINED), escalate.subject)
        assertTrue(escalate.reason.contains("EVIDENCE_ABSENT"))
        assertEquals("scout", escalate.context["assignedTo"])
    }

    @Test
    fun `a second violation before any resolver pass still does not ask the human`() {
        val open = VerdictPolicy.OpenVerdictThread(violated, partFit, BlueprintRoster.scout.id, resolverPasses = 0)

        val actions = policy.decide(verdict("duct", ProbeVerdict.Violated("still undersized")), open = listOf(open))

        assertEquals(listOf(VerdictPolicy.Action.PostCard::class), actions.map { it::class })
    }

    @Test
    fun `a violation unresolved after one scout pass asks the human`() {
        val open = VerdictPolicy.OpenVerdictThread(violated, partFit, BlueprintRoster.scout.id, resolverPasses = 1)

        val actions = policy.decide(verdict("duct", ProbeVerdict.Violated("still undersized")), open = listOf(open))

        assertEquals(
            listOf(VerdictPolicy.Action.PostCard::class, VerdictPolicy.Action.Escalate::class),
            actions.map { it::class },
        )
    }

    @Test
    fun `an escalated thread is not escalated twice`() {
        val open = VerdictPolicy.OpenVerdictThread(
            violated,
            partFit,
            BlueprintRoster.scout.id,
            resolverPasses = 2,
            escalated = true,
        )

        val actions = policy.decide(verdict("duct", ProbeVerdict.Violated("still undersized")), open = listOf(open))

        assertTrue(actions.none { it is VerdictPolicy.Action.Escalate })
    }

    @Test
    fun `holds resolves the open thread the same probe convicted`() {
        val open = VerdictPolicy.OpenVerdictThread(violated, partFit, BlueprintRoster.scout.id, resolverPasses = 1)

        val actions = policy.decide(verdict("duct", ProbeVerdict.Holds("6in duct fits")), open = listOf(open))

        assertEquals(
            listOf(VerdictPolicy.Action.PostCard::class, VerdictPolicy.Action.Resolve::class),
            actions.map { it::class },
        )
        assertEquals("6in duct fits", assertIs<VerdictPolicy.Action.Resolve>(actions[1]).reason)
    }

    @Test
    fun `holds from a different probe leaves the thread open`() {
        val open = VerdictPolicy.OpenVerdictThread(violated, partFit, BlueprintRoster.scout.id)

        val actions = policy.decide(verdict("duct", ProbeVerdict.Holds(), probeId = sequence), open = listOf(open))

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `holds with nothing open does nothing and warn opens nothing`() {
        assertTrue(policy.decide(verdict("duct", ProbeVerdict.Holds()), open = emptyList()).isEmpty())
        assertTrue(
            policy.decide(verdict("fan", ProbeVerdict.Warn("210 CFM filter on a 226 CFM fan")), emptyList()).isEmpty(),
        )
    }
}
