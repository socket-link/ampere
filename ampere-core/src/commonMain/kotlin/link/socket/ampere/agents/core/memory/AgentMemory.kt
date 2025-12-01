package link.socket.ampere.agents.core.memory

import kotlinx.serialization.Serializable

@Serializable
data class AgentMemory(
    val currentMemoryCell: AgentMemoryCell.Current,
    val pastMemoryCell: AgentMemoryCell.Past,
    val additionalMemoryCells: List<AgentMemoryCell>,
) {
    companion object {
        val blank: AgentMemory = AgentMemory(
            currentMemoryCell = AgentMemoryCell.Current.blank,
            pastMemoryCell = AgentMemoryCell.Past.blank,
            additionalMemoryCells = emptyList(),
        )
    }
}
