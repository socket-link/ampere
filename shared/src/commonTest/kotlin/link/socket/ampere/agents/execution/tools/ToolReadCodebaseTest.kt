package link.socket.ampere.agents.execution.tools

expect class ToolReadCodebaseTest {
    fun `validateParameters requires path string`()
    fun `execute reads file content`()
    fun `execute lists directory contents`()
    fun `execute returns error for non-existent path`()
    fun `execute blocks traversal outside root directory`()
}
