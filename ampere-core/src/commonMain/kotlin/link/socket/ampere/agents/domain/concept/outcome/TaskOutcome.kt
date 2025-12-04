package link.socket.ampere.agents.domain.concept.outcome

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.task.Task

@Serializable
sealed interface TaskOutcome : Outcome {

    val task: Task

    @Serializable
    sealed interface Success : TaskOutcome, Outcome.Success {

        @Serializable
        data class Partial(
            override val id: OutcomeId,
            override val task: Task,
            val unfinishedTasks: List<Task>? = null,
        ) : Success

        @Serializable
        data class Full(
            override val id: OutcomeId,
            override val task: Task,
            val value: String,
        ) : Success
    }

    @Serializable
    data class Failure(
        override val id: OutcomeId,
        override val task: Task,
        val errorMessage: String,
    ) : TaskOutcome, Outcome.Failure
}
