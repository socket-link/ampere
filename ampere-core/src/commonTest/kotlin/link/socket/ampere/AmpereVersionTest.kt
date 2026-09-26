package link.socket.ampere

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmpereVersionTest {

    @Test
    fun `the runtime version is an orderable version`() {
        assertEquals(0, compareAmpereVersions(AMPERE_RUNTIME_VERSION, AMPERE_RUNTIME_VERSION))
    }

    @Test
    fun `a newer version orders above an older one`() {
        assertTrue(compareAmpereVersions("0.16.0", "0.15.0")!! > 0)
        assertTrue(compareAmpereVersions("0.15.0", "0.16.0")!! < 0)
        assertTrue(compareAmpereVersions("1.0.0", "0.99.99")!! > 0)
    }

    @Test
    fun `a patch bump orders above its release`() {
        assertTrue(compareAmpereVersions("0.15.1", "0.15.0")!! > 0)
    }

    @Test
    fun `a missing component reads as zero`() {
        assertEquals(0, compareAmpereVersions("0.15", "0.15.0"))
        assertEquals(0, compareAmpereVersions("0.15.0.0", "0.15"))
        assertTrue(compareAmpereVersions("0.15.1", "0.15")!! > 0)
    }

    @Test
    fun `pre-release and build metadata are ignored`() {
        // A host running its own release candidate must not reject a bundle
        // pinned to the version that host is about to be: 0.16.0-rc.1 has the
        // same CanonType entries as 0.16.0.
        assertEquals(0, compareAmpereVersions("0.16.0", "0.16.0-rc.1"))
        assertEquals(0, compareAmpereVersions("0.16.0+build.7", "0.16.0"))
        assertTrue(compareAmpereVersions("0.17.0-rc.1", "0.16.0")!! > 0)
    }

    @Test
    fun `a version that cannot be ordered returns null`() {
        assertNull(compareAmpereVersions("latest", "0.15.0"))
        assertNull(compareAmpereVersions("1.x", "0.15.0"))
        assertNull(compareAmpereVersions("", "0.15.0"))
        assertNull(compareAmpereVersions("0.15.0", "not-a-version"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(0, compareAmpereVersions(" 0.15.0 ", "0.15.0"))
    }
}
