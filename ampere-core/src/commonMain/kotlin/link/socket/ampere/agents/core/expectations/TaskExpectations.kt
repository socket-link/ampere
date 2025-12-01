package link.socket.ampere.agents.core.expectations

import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.outcomes.TaskOutcome

@Serializable
class TaskExpectations(
    val requirementsDescription: String,
    val expectedOutcomes: List<KClass<TaskOutcome>>,
)
