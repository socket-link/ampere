package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.field.Admission
import link.socket.ampere.field.Hardness
import link.socket.ampere.field.Resistance
import link.socket.ampere.field.Rule
import link.socket.ampere.plug.permission.UserGrants

/**
 * AMPR-511: `Rule.Window`, a recurring time-of-day window the Breaker reads on its own
 * clock in the rule's zone.
 *
 * Every case pins the clock to a local time in [CHICAGO]. 2026-10-30 is a Friday, so the
 * midnight-wrapping window that opens on Fridays only covers Saturday's early morning and
 * not Friday's. The selector cases pin the other wrong answer that looks right: a step with
 * no tool is never inside a window over named tools, which is what keeps a Window trip one
 * step's failure when a hosted run asks its run rules again with `tool = null`.
 */
class BreakerWindowTest {

    @Test
    fun `a step inside a same-day window trips naming the window and the local time and the tool`() = runTest {
        val admission = admitAt(FRIDAY, 12, 30, WORKDAY)

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertEquals(
            "quiet window: it is 12:30 America/Chicago, inside 09:00–17:00; 'send_email' may not run",
            trip.reason,
        )
    }

    @Test
    fun `a same-day window opens on its from and closes on its until`() = runTest {
        assertIs<Admission.Pass>(admitAt(FRIDAY, 8, 59, WORKDAY).getOrThrow(), "a minute before it opens")
        assertIs<Admission.Trip>(admitAt(FRIDAY, 9, 0, WORKDAY).getOrThrow(), "the minute it opens")
        assertIs<Admission.Trip>(admitAt(FRIDAY, 16, 59, WORKDAY).getOrThrow(), "its last minute")
        assertIs<Admission.Pass>(admitAt(FRIDAY, 17, 0, WORKDAY).getOrThrow(), "the minute it closes")
    }

    @Test
    fun `a midnight-wrapping window trips before midnight on a day it opens`() = runTest {
        val admission = admitAt(FRIDAY, 22, 14, FRIDAY_NIGHTS)

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertEquals(
            "quiet window: it is 22:14 America/Chicago, inside 22:00–07:00; 'send_email' may not run",
            trip.reason,
        )
    }

    @Test
    fun `a midnight-wrapping window trips after midnight on the morning after a day it opens`() = runTest {
        // Saturday is not in the window's days; Friday, the day it opened, is.
        val admission = admitAt(SATURDAY, 6, 30, FRIDAY_NIGHTS)

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertTrue(trip.reason.contains("it is 06:30 America/Chicago"), "got '${trip.reason}'")
    }

    @Test
    fun `a midnight-wrapping window belongs to the day it opens on`() = runTest {
        // Friday morning is the tail of a Thursday window, and Thursday is not in its days.
        assertIs<Admission.Pass>(admitAt(FRIDAY, 6, 30, FRIDAY_NIGHTS).getOrThrow(), "Friday morning")
        // Saturday night would open a Saturday window, and Saturday is not in its days.
        assertIs<Admission.Pass>(admitAt(SATURDAY, 22, 30, FRIDAY_NIGHTS).getOrThrow(), "Saturday night")
    }

    @Test
    fun `a midnight-wrapping window opens on its from and closes on its until`() = runTest {
        assertIs<Admission.Pass>(admitAt(FRIDAY, 21, 59, FRIDAY_NIGHTS).getOrThrow(), "a minute before it opens")
        assertIs<Admission.Trip>(admitAt(FRIDAY, 22, 0, FRIDAY_NIGHTS).getOrThrow(), "the minute it opens")
        assertIs<Admission.Trip>(admitAt(SATURDAY, 6, 59, FRIDAY_NIGHTS).getOrThrow(), "its last minute")
        assertIs<Admission.Pass>(admitAt(SATURDAY, 7, 0, FRIDAY_NIGHTS).getOrThrow(), "the minute it closes")
    }

    @Test
    fun `the window is read in its own zone and not in the clock's or the device's`() = runTest {
        // Friday 22:14 in Chicago is Saturday 12:14 in Tokyo: outside a Tokyo night window.
        val tokyoNights = FRIDAY_NIGHTS.copy(timezone = "Asia/Tokyo", days = DayOfWeek.entries.toSet())

        assertIs<Admission.Pass>(admitAt(FRIDAY, 22, 14, tokyoNights).getOrThrow())
        assertIs<Admission.Trip>(admitAt(FRIDAY, 22, 14, tokyoNights.copy(timezone = CHICAGO_ID)).getOrThrow())
    }

    @Test
    fun `a tool the window does not name passes`() = runTest {
        val admission = admitAt(FRIDAY, 12, 30, WORKDAY, tool = READ_TOOL)

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    @Test
    fun `a seat-namespaced tool is inside a window that names its bare id`() = runTest {
        val seated = tool("doer/send_email")

        val trip = assertIs<Admission.Trip>(admitAt(FRIDAY, 12, 30, WORKDAY, tool = seated).getOrThrow())
        assertTrue(trip.reason.contains("'doer/send_email' may not run"), "got '${trip.reason}'")
    }

    @Test
    fun `a step with no tool passes a window over named tools`() = runTest {
        val admission = admitAt(FRIDAY, 12, 30, WORKDAY, tool = null)

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    @Test
    fun `a step with no tool is inside a window that names no tools`() = runTest {
        val admission = admitAt(FRIDAY, 12, 30, WORKDAY.copy(appliesTo = emptySet()), tool = null)

        val trip = assertIs<Admission.Trip>(admission.getOrThrow())
        assertEquals(
            "quiet window: it is 12:30 America/Chicago, inside 09:00–17:00; this step may not run",
            trip.reason,
        )
    }

    @Test
    fun `a run-level check with no tool is not tripped by a Hard window over named tools`() = runTest {
        // RosterHostedRun's quotaReached asks the run rules this way to tell a reached quota
        // from one step's fault: a pass keeps a Window trip an ordinary, non-critical failure.
        val window = WORKDAY.copy(appliesTo = setOf("x"))

        val admission = breakerAt(at(FRIDAY, 12, 30)).admit(tool = null, rules = listOf(window to Hardness.HARD))

        assertEquals(Admission.Pass, admission.getOrThrow())
    }

    @Test
    fun `a zone the platform cannot resolve is a failure and never a pass`() = runTest {
        val unknown = WORKDAY.copy(timezone = "Mars/Olympus_Mons")

        // Outside the window's hours and outside its selector: still not a pass.
        val admission = admitAt(FRIDAY, 3, 0, unknown, tool = READ_TOOL)

        val failure = assertIs<IllegalStateException>(admission.exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("Mars/Olympus_Mons"), "got '${failure.message}'")
    }

    @Test
    fun `a Soft window resists with the rule listed`() = runTest {
        val admitted = breakerAt(at(FRIDAY, 12, 30))
            .admitDetailed(EMAIL_TOOL, listOf(WORKDAY to Hardness.SOFT))
            .getOrThrow()

        assertEquals(Admission.Resist(Resistance(1.0)), admitted.admission)
        assertEquals(listOf(WORKDAY to Hardness.SOFT), admitted.breached)
    }

    private suspend fun admitAt(
        day: LocalDateTime,
        hour: Int,
        minute: Int,
        window: Rule.Window,
        tool: Tool<*>? = EMAIL_TOOL,
    ): Result<Admission> = breakerAt(at(day, hour, minute)).admit(tool, listOf(window to Hardness.HARD))

    private fun breakerAt(now: Instant): Breaker = Breaker(
        userGrants = { UserGrants() },
        actorId = AGENT_ID,
        clock = object : Clock {
            override fun now(): Instant = now
        },
    )

    private companion object {
        const val AGENT_ID = "breaker-window-test-agent"
        const val CHICAGO_ID = "America/Chicago"
        val CHICAGO: TimeZone = TimeZone.of(CHICAGO_ID)

        val FRIDAY = LocalDateTime(2026, 10, 30, 0, 0)
        val SATURDAY = LocalDateTime(2026, 10, 31, 0, 0)

        val EMAIL_TOOL: Tool<*> = tool("send_email")
        val READ_TOOL: Tool<*> = tool("read_inbox")

        /** 09:00–17:00 every day, over `send_email`. */
        val WORKDAY = Rule.Window(
            from = LocalTime(9, 0),
            until = LocalTime(17, 0),
            timezone = CHICAGO_ID,
            appliesTo = setOf("send_email"),
        )

        /** 22:00–07:00, opening on Fridays only, over `send_email`. */
        val FRIDAY_NIGHTS = Rule.Window(
            from = LocalTime(22, 0),
            until = LocalTime(7, 0),
            timezone = CHICAGO_ID,
            days = setOf(DayOfWeek.FRIDAY),
            appliesTo = setOf("send_email"),
        )

        fun at(day: LocalDateTime, hour: Int, minute: Int): Instant =
            LocalDateTime(day.year, day.monthNumber, day.dayOfMonth, hour, minute).toInstant(CHICAGO)

        fun tool(id: String): Tool<*> = FunctionTool<ExecutionContext>(
            id = id,
            name = id,
            description = "test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { error("the Breaker never runs a tool") },
        )
    }
}
