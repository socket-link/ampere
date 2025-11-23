package link.socket.ampere.agents.events.meetings

import link.socket.ampere.agents.events.EventSource

/**
 * Functional interface for scheduling meetings.
 *
 * This interface decouples the TicketOrchestrator from MeetingOrchestrator,
 * allowing them to be composed by a higher-level orchestrator without
 * direct dependencies. This enables bidirectional communication between
 * orchestrators through the parent EnvironmentOrchestrator.
 */
fun interface MeetingSchedulingService {
    /**
     * Schedule a meeting.
     *
     * @param meeting The meeting to schedule
     * @param scheduledBy The event source that is scheduling the meeting
     * @return Result containing the scheduled meeting with updated thread info
     */
    suspend fun scheduleMeeting(
        meeting: Meeting,
        scheduledBy: EventSource,
    ): Result<Meeting>
}
