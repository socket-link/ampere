package link.socket.ampere.agents.execution.tools

expect class ToolWriteCodeFileTest {
    fun `validateParameters enforces filePath and content strings`()
    fun `execute writes file with content and creates parent directories`()
    fun `execute fails gracefully on invalid path`()
}
