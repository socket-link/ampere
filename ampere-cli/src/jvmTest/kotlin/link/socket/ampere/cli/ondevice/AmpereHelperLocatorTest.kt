package link.socket.ampere.cli.ondevice

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Where the CLI looks for `ampere-helper` (AMPR-375), and that every miss is a reason. A host with
 * no helper must behave exactly as the CLI did before the helper existed, so the answer is never
 * an exception.
 */
class AmpereHelperLocatorTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `a host that is not a Mac has no helper and says so`() {
        val located = locator(osName = "Linux", named = executable("ampere-helper").path).locate()

        assertTrue(assertIs<AmpereHelperBinary.Absent>(located).reason.contains("macOS"))
    }

    @Test
    fun `a named helper is used when it is there`() {
        val helper = executable("named-helper")

        assertEquals(helper, assertIs<AmpereHelperBinary.Present>(locator(named = helper.path).locate()).file)
    }

    @Test
    fun `a named helper that is missing or not executable is reported, not skipped`() {
        // Installed where the CLI would otherwise find one: a name set on purpose must not fall through.
        val install = Files.createDirectories(directory.resolve("install")).toFile()
        executable("install/bin/${AmpereHelperLocator.BINARY_NAME}")
        val notExecutable = directory.resolve("plain-file").toFile().apply { writeText("#!/bin/sh\n") }

        val missing = locator(named = directory.resolve("nowhere").toString(), installDirectory = install).locate()
        val unusable = locator(named = notExecutable.path, installDirectory = install).locate()

        assertTrue(assertIs<AmpereHelperBinary.Absent>(missing).reason.contains("nowhere"))
        assertTrue(assertIs<AmpereHelperBinary.Absent>(unusable).reason.contains("not executable"))
    }

    @Test
    fun `the environment names a helper when the property does not`() {
        val helper = executable("env-helper")
        val locator = AmpereHelperLocator(
            osName = "Mac OS X",
            property = { null },
            environment = { if (it == AmpereHelperLocator.HELPER_ENVIRONMENT) helper.path else null },
            workingDirectory = directory.toFile(),
            installDirectory = null,
        )

        assertEquals(helper, assertIs<AmpereHelperBinary.Present>(locator.locate()).file)
    }

    @Test
    fun `an installed CLI finds the helper beside its start script`() {
        val install = Files.createDirectories(directory.resolve("ampere-jvm")).toFile()
        val helper = executable("ampere-jvm/bin/${AmpereHelperLocator.BINARY_NAME}")

        assertEquals(helper, assertIs<AmpereHelperBinary.Present>(locator(installDirectory = install).locate()).file)
    }

    @Test
    fun `a CLI run from the repository finds the SwiftPM build`() {
        val helper = executable(AmpereHelperLocator.SOURCE_TREE_PATH)

        assertEquals(helper, assertIs<AmpereHelperBinary.Present>(locator().locate()).file)
    }

    @Test
    fun `no helper anywhere is a reason that says where it looked`() {
        val located = locator(installDirectory = directory.resolve("ampere-jvm").toFile()).locate()

        val reason = assertIs<AmpereHelperBinary.Absent>(located).reason
        assertTrue(reason.contains("ampere-jvm"), reason)
        assertTrue(reason.contains(AmpereHelperLocator.SOURCE_TREE_PATH), reason)
    }

    private fun locator(
        osName: String = "Mac OS X",
        named: String? = null,
        installDirectory: File? = null,
    ) = AmpereHelperLocator(
        osName = osName,
        property = { if (it == AmpereHelperLocator.HELPER_PROPERTY) named else null },
        environment = { null },
        workingDirectory = directory.toFile(),
        installDirectory = installDirectory,
    )

    private fun executable(relative: String): File {
        val file = directory.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, "#!/bin/sh\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file.toFile()
    }
}
