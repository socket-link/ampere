package link.socket.ampere.agents.core.expectations

import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.outcomes.MeetingOutcome

/** A concrete outcome produced by a meeting (decision, action item, blocker). */
@Serializable
data class MeetingExpectations(
    val requirementsDescription: String,
    val expectedOutcomes: List<KClass<out MeetingOutcome>>,
) : Expectations
