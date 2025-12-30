package link.socket.ampere.agents.domain.expectation

import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.outcome.TaskOutcome

@Serializable
class TaskExpectation(
    val requirementsDescription: String,
    val expectedOutcomes: List<KClass<TaskOutcome>>,
)
