package link.socket.ampere.agents.domain.expectation

import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.outcome.MeetingOutcome

/** A concrete outcome produced by a meeting (decision, action item, blocker). */
@Serializable
data class MeetingExpectation(
    val requirementsDescription: String,
    val expectedOutcomes: List<KClass<out MeetingOutcome>>,
) : Expectations
