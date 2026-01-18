package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertContains

class JazzDemoCommandTest {

    @Test
    fun `jazz demo help text mentions ObservabilitySpark task`() {
        val command = JazzDemoCommand { error("context should not be needed for help output") }
        val result = command.test("--help")

        assertContains(result.output, "Jazz Test demo")
        assertContains(result.output, "ObservabilitySpark")
    }
}
