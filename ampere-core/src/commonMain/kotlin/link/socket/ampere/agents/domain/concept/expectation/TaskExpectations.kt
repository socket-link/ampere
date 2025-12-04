package link.socket.ampere.agents.domain.concept.expectation

import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.outcome.TaskOutcome

@Serializable
class TaskExpectations(
    val requirementsDescription: String,
    val expectedOutcomes: List<KClass<TaskOutcome>>,
)
