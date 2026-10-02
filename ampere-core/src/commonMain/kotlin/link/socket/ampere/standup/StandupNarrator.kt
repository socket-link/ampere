package link.socket.ampere.standup

import kotlinx.datetime.Instant
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.room.RoomId
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RosterPrompts

/**
 * Everything the Coordinator knows when it writes the status post (AMPR-379).
 * Primitives only, so a narrator — templated or model-backed — needs nothing else.
 */
data class StandupBrief(
    val roomId: RoomId,
    val projectName: String,
    val since: Instant,
    val now: Instant,
    val eventsSince: Int,
    val completed: Int,
    val started: Int,
    val remaining: Int,
    val blocked: List<String>,
    val openVerdicts: List<String>,
    val proposal: RevisionProposal,
    val revisionReleased: Boolean,
)

/**
 * Writes the standup narrative: the one place a standup may spend Watts.
 *
 * The lifecycle is deterministic and 0W; re-plans between standups are graph work.
 * The narrative is the single metered cloud step the ticket allows, and
 * [TemplatedNarrator] is the acceptable v1 that spends nothing.
 */
fun interface StandupNarrator {
    suspend fun narrate(brief: StandupBrief): Result<String>
}

/** The 0W narrator: a status the user can read in thirty seconds, from the brief alone. */
object TemplatedNarrator : StandupNarrator {

    override suspend fun narrate(brief: StandupBrief): Result<String> = Result.success(render(brief))

    fun render(brief: StandupBrief): String = buildString {
        append("Standup for ${brief.projectName}. ")
        append("Since ${brief.since}: ${brief.completed} task(s) finished, ${brief.started} started, ")
        append("${brief.remaining} remaining. ")
        append(
            when (val finish = brief.proposal.projectedFinish) {
                null -> if (brief.remaining == 0) {
                    "Nothing left to schedule. "
                } else {
                    "No finish projected: not enough availability. "
                }
                else -> "Projected finish $finish. "
            },
        )
        append(
            if (brief.revisionReleased) {
                "Revision ${brief.proposal.revision} released by the Inspector. "
            } else {
                "Revision ${brief.proposal.revision} withheld by the Inspector. "
            },
        )
        if (brief.blocked.isNotEmpty()) append("Blocked: ${brief.blocked.joinToString("; ")}. ")
        if (brief.openVerdicts.isNotEmpty()) append("Open verdicts: ${brief.openVerdicts.joinToString("; ")}. ")
        if (brief.blocked.isEmpty() && brief.openVerdicts.isEmpty()) append("Nothing is blocked.")
    }.trim()
}

/**
 * The metered narrator: one call through the custom-provider seam every agent in
 * the repo already fakes in tests ([LlmProvider]), under the Coordinator's
 * versioned prompt. The prompt is assembled the way `AgentLLMService` assembles it
 * for a custom provider — `System:` then `User:` — so a provider that serves the
 * agents serves this.
 *
 * Falls back to [TemplatedNarrator] when the provider fails: a standup without a
 * narrative is not a standup, and the template is always available.
 */
class LlmNarrator(
    private val provider: LlmProvider,
    private val instructions: PromptRef = RosterPrompts.COORDINATOR,
) : StandupNarrator {

    override suspend fun narrate(brief: StandupBrief): Result<String> {
        val system = RosterPrompts.text(instructions) ?: return TemplatedNarrator.narrate(brief)
        val prompt = buildString {
            append("System: ")
            appendLine(system)
            appendLine()
            append("User: ")
            appendLine("Write the standup status post from this brief.")
            appendLine(TemplatedNarrator.render(brief))
            brief.proposal.rationale.forEach { appendLine("- $it") }
        }
        return runCatching { provider(prompt).trim() }
            .mapCatching { text -> text.ifBlank { TemplatedNarrator.render(brief) } }
            .recoverCatching { TemplatedNarrator.render(brief) }
    }
}
