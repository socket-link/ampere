package link.socket.ampere.room

import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster

/**
 * What a Room does when a Probe reaches a verdict (AMPR-379). Pure: the decision
 * is a list of [Action]s over the verdict and the verdict threads currently open,
 * so the rules are testable without a bus, a store, or a clock.
 *
 * The rules:
 *
 * - **Violated** opens a thread assigned to the role that can resolve it
 *   ([Roster.resolverFor]) and posts the verdict card. The human is *not* contacted
 *   until the assigned role has taken one pass and the Probe still convicts — a
 *   second `Violated` on an open thread with at least one resolver pass escalates.
 * - **Undetermined** opens its thread, posts the card, and escalates at once: no
 *   evidence exists, and finding none is the one outcome a role cannot resolve.
 * - **Holds** closes every open thread the same Probe convicted on that subject.
 * - **Warn** opens nothing: decided, bad, not disqualifying.
 *
 * Escalation is a one-shot per thread; a thread already waiting for the human is
 * not escalated again when the Probe reconvicts.
 */
class VerdictPolicy(private val roster: Roster) {

    /** A verdict thread the Room has open, as the policy needs to see it. */
    data class OpenVerdictThread(
        val subject: ThreadSubject.Verdict,
        val probeId: ProbeId,
        val assignedTo: RoleId,
        val resolverPasses: Int = 0,
        val escalated: Boolean = false,
    )

    sealed interface Action {
        val subject: ThreadSubject.Verdict

        data class OpenThread(
            override val subject: ThreadSubject.Verdict,
            val probeId: ProbeId,
            val assignedTo: RoleId,
            val title: String,
        ) : Action

        data class PostCard(
            override val subject: ThreadSubject.Verdict,
            val body: String,
            val card: RoomCard.Verdict,
        ) : Action

        data class Escalate(
            override val subject: ThreadSubject.Verdict,
            val reason: String,
            val context: Map<String, String>,
        ) : Action

        data class Resolve(
            override val subject: ThreadSubject.Verdict,
            val reason: String,
        ) : Action
    }

    fun decide(verdict: ProbeEvent.VerdictReached, open: Collection<OpenVerdictThread>): List<Action> =
        when (val value = verdict.verdict) {
            is ProbeVerdict.Holds ->
                open
                    .filter { it.subject.subjectId == verdict.subjectId && it.probeId == verdict.probeId }
                    .flatMap { thread ->
                        listOf(
                            Action.PostCard(
                                thread.subject,
                                "Probe ${verdict.probeId.value} holds on ${verdict.subjectId}",
                                card(verdict),
                            ),
                            Action.Resolve(thread.subject, value.reason ?: "Probe ${verdict.probeId.value} holds"),
                        )
                    }

            is ProbeVerdict.Warn -> emptyList()

            is ProbeVerdict.Violated -> convict(
                verdict = verdict,
                subject = ThreadSubject.Verdict(verdict.subjectId, VerdictKind.VIOLATED),
                open = open,
                reason = value.reason,
                escalateWhen = { thread -> thread.resolverPasses >= 1 && !thread.escalated },
            )

            is ProbeVerdict.Undetermined -> convict(
                verdict = verdict,
                subject = ThreadSubject.Verdict(verdict.subjectId, VerdictKind.UNDETERMINED),
                open = open,
                reason = "${value.reason} (${value.cause.name})",
                escalateWhen = { thread -> !thread.escalated },
            )
        }

    private fun convict(
        verdict: ProbeEvent.VerdictReached,
        subject: ThreadSubject.Verdict,
        open: Collection<OpenVerdictThread>,
        reason: String,
        escalateWhen: (OpenVerdictThread) -> Boolean,
    ): List<Action> {
        val existing = open.firstOrNull { it.subject == subject }
        val resolver = existing?.assignedTo ?: roster.resolverFor(verdict.probeId)
        val card = Action.PostCard(
            subject = subject,
            body = "Probe ${verdict.probeId.value} on ${verdict.subjectId}: " +
                "${subject.kind.name.lowercase()} — $reason",
            card = card(verdict),
        )
        val escalate = Action.Escalate(
            subject = subject,
            reason = "${subject.kind.name.lowercase()} verdict on ${verdict.subjectId}: $reason",
            context = mapOf(
                "subjectId" to verdict.subjectId,
                "probeId" to verdict.probeId.value,
                "kind" to subject.kind.name,
                "assignedTo" to resolver.value,
            ),
        )

        return buildList {
            if (existing == null) {
                add(
                    Action.OpenThread(
                        subject = subject,
                        probeId = verdict.probeId,
                        assignedTo = resolver,
                        title = "${subject.kind.name.lowercase().replaceFirstChar { it.uppercase() }}: " +
                            verdict.subjectId,
                    ),
                )
            }
            add(card)
            val shouldEscalate = if (existing == null) {
                subject.kind == VerdictKind.UNDETERMINED
            } else {
                escalateWhen(existing)
            }
            if (shouldEscalate) add(escalate)
        }
    }

    private fun card(verdict: ProbeEvent.VerdictReached): RoomCard.Verdict =
        RoomCard.Verdict(probeId = verdict.probeId, subjectId = verdict.subjectId, verdict = verdict.verdict)
}
