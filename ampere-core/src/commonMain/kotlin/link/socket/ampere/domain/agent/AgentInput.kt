package link.socket.ampere.domain.agent

sealed class AgentInput(
    open val key: String,
    open val name: String,
    open val value: String,
) {
    data class EnumArgs(
        override val key: String,
        override val name: String,
        override val value: String,
        val possibleValues: List<String>,
    ) : AgentInput(key, name, value)

    data class StringArg(
        override val key: String,
        override val name: String,
        override val value: String,
    ) : AgentInput(key, name, value)

    data class ListArg(
        override val key: String,
        override val name: String,
        val listValue: List<String>,
    ) : AgentInput(key, name, listValue.joinToString(", "))
}
