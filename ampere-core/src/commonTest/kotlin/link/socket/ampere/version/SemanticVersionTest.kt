package link.socket.ampere.version

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AMPR-363: the comparison behind the trace version-skew verdict. */
class SemanticVersionTest {

    @Test
    fun `parses a full triple`() {
        assertEquals(SemanticVersion(0, 15, 3), SemanticVersion.parseOrNull("0.15.3"))
    }

    @Test
    fun `defaults the segments a version omits`() {
        assertEquals(SemanticVersion(1, 0, 0), SemanticVersion.parseOrNull("1"))
        assertEquals(SemanticVersion(1, 2, 0), SemanticVersion.parseOrNull("1.2"))
    }

    @Test
    fun `strips a pre-release or build suffix`() {
        assertEquals(SemanticVersion(0, 16, 0), SemanticVersion.parseOrNull("0.16.0-SNAPSHOT"))
        assertEquals(SemanticVersion(0, 16, 0), SemanticVersion.parseOrNull("0.16.0+sha.abc123"))
    }

    @Test
    fun `returns null for a version it cannot read`() {
        assertNull(SemanticVersion.parseOrNull(""))
        assertNull(SemanticVersion.parseOrNull("   "))
        assertNull(SemanticVersion.parseOrNull("not-a-version"))
        assertNull(SemanticVersion.parseOrNull("1.x.0"))
        assertNull(SemanticVersion.parseOrNull("-1.0.0"))
        assertNull(SemanticVersion.parseOrNull("1.2.3.4"))
    }

    @Test
    fun `orders major then minor then patch`() {
        assertTrue(SemanticVersion(1, 0, 0) > SemanticVersion(0, 99, 99))
        assertTrue(SemanticVersion(0, 16, 0) > SemanticVersion(0, 15, 9))
        assertTrue(SemanticVersion(0, 15, 1) > SemanticVersion(0, 15, 0))
        assertEquals(0, SemanticVersion(0, 15, 0).compareTo(SemanticVersion(0, 15, 0)))
    }

    @Test
    fun `isNewer is strict`() {
        assertTrue(SemanticVersion.isNewer(candidate = "0.16.0", reference = "0.15.0"))
        assertFalse(SemanticVersion.isNewer(candidate = "0.15.0", reference = "0.15.0"))
        assertFalse(SemanticVersion.isNewer(candidate = "0.14.0", reference = "0.15.0"))
    }

    @Test
    fun `a snapshot of the current release is not newer than it`() {
        // Deliberate: ordering pre-releases needs the full SemVer rules and nothing needs them
        // yet, so a snapshot never manufactures a skew against its own release.
        assertFalse(SemanticVersion.isNewer(candidate = "0.15.0-SNAPSHOT", reference = "0.15.0"))
    }

    @Test
    fun `an unreadable version on either side is never newer`() {
        assertFalse(SemanticVersion.isNewer(candidate = "next", reference = "0.15.0"))
        assertFalse(SemanticVersion.isNewer(candidate = "0.16.0", reference = "next"))
    }

    @Test
    fun `the generated build version is itself parseable`() {
        // The one assertion that ties the hand-written comparison to the generated constant: a
        // version the comparison cannot read would silently disable every skew check.
        assertTrue(AMPERE_VERSION.isNotBlank())
        assertNotNull(SemanticVersion.parseOrNull(AMPERE_VERSION))
    }
}
