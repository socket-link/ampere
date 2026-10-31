package link.socket.ampere.agents.execution.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateCommandTest {

    @Test
    fun `quotes group, single quotes are literal, and env references survive splitting`() {
        assertEquals(
            listOf("xcodebuild", "test", "-destination", "id=\${env.SIMULATOR_UDID}", "CODE_SIGN_IDENTITY=", "a b"),
            GateCommand.argv("xcodebuild test -destination \"id=\${env.SIMULATOR_UDID}\" CODE_SIGN_IDENTITY= 'a b'"),
        )
        assertEquals(listOf("echo", "say \"hi\"", "\$x"), GateCommand.argv("""echo "say \"hi\"" '${'$'}x'"""))
    }

    @Test
    fun `an unclosed quote or an empty command is malformed`() {
        assertFailsWith<GateCommand.Malformed> { GateCommand.argv("echo 'oops") }
        assertFailsWith<GateCommand.Malformed> { GateCommand.argv("   ") }
    }

    @Test
    fun `substitution is per argument, and an unset variable is named rather than emptied`() {
        val argv = GateCommand.argv("tool --to=\${env.DEST} \${env.DEST}")
        assertEquals(
            GateCommand.Substitution.Done(listOf("tool", "--to=a b", "a b")),
            GateCommand.substitute(argv, mapOf("DEST" to "a b")),
        )
        val unset = assertIs<GateCommand.Substitution.Unset>(GateCommand.substitute(argv, emptyMap()))
        assertEquals(listOf("DEST"), unset.names)
        assertIs<GateCommand.Substitution.Unset>(GateCommand.substitute(argv, mapOf("DEST" to "")))
    }

    @Test
    fun `gradle tasks skip options and option values`() {
        assertEquals(
            listOf("ktlintCheck"),
            GateCommand.gradleTasks(
                listOf("./gradlew", "ktlintCheck", "-x", ":common:a", "--exclude-task", ":b", "--offline", "-Pk=v"),
            ),
        )
        val filtered = listOf("./gradlew", ":a:jvmTest", "--tests", "*Foo*")
        assertEquals(listOf(":a:jvmTest"), GateCommand.gradleTasks(filtered))
    }

    @Test
    fun `bundling is one task for gradle, one action for xcodebuild, and any other tool is taken at its word`() {
        assertNotNull(GateCommand.bundling(listOf("./gradlew", ":a:jvmTest", ":b:jvmTest")))
        assertNull(GateCommand.bundling(listOf("./gradlew", ":a:jvmTest")))
        assertNotNull(GateCommand.bundling(listOf("xcodebuild", "clean", "test", "-scheme", "x")))
        assertNull(GateCommand.bundling(listOf("xcodebuild", "test-without-building", "-scheme", "test")))
        assertNull(GateCommand.bundling(listOf("make", "lint", "test")))
    }

    @Test
    fun `fresh gradle disables the build cache, reruns the gate's own task, and deletes its own results`() {
        val plan = GateCommand.freshGradle(listOf("./gradlew", ":ampere-core:jvmTest", "--tests", "*Foo*"))

        assertEquals(
            listOf("./gradlew", "--no-build-cache", ":ampere-core:jvmTest", "--rerun", "--tests", "*Foo*"),
            plan.argv,
        )
        assertEquals(
            listOf("ampere-core/build/test-results/jvmTest", "ampere-core/build/reports/tests/jvmTest"),
            plan.deletions,
        )

        val root = GateCommand.freshGradle(listOf("gradle", "test"))
        assertEquals(listOf("gradle", "--no-build-cache", "test", "--rerun"), root.argv)
        assertTrue("build/test-results/test" in root.deletions)

        val nested = GateCommand.freshGradle(listOf("./gradlew", ":tooling:admin:jvmTest"))
        assertTrue("tooling/admin/build/test-results/jvmTest" in nested.deletions)
    }
}
