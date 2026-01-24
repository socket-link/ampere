package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertContains

class DemoCommandTest {

    @Test
    fun `demo help text mentions ObservabilitySpark task`() {
        val command = DemoCommand { error("context should not be needed for help output") }
        val result = command.test("--help")

        assertContains(result.output, "AMPERE demo")
        assertContains(result.output, "ObservabilitySpark")
    }
}
