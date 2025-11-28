package link.socket.ampere.agents.execution.tools

expect class ToolRunTestsTest {
    fun `validateParameters allows optional testPath`()
    fun `execute runs tests and captures output success`()
    fun `execute passes test filter when provided`()
    fun `execute handles failure gracefully`()
}
