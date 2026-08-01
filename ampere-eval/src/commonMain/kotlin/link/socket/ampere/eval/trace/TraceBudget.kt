package link.socket.ampere.eval.trace

/**
 * Size contract enforced on every recorded [Trace] at [RecordingHandle.stop] (AMPR-267).
 *
 * Two independent bounds, per AMPR-267 task 1 ("per event, per trace, or both"):
 * a per-event bound (a single event's serialized payload cannot grow unbounded
 * from a free-text field) and a per-trace bound (a pathological run cannot grow
 * the persisted blob without limit). Both are enforced write-side in
 * [RecordingHandle.stop] via truncate-and-flag ([TraceEvent.truncated]) and
 * drop-with-a-marker ([Trace.droppedEventCount]) — never a read-side reject —
 * so an already-persisted [Trace] always decodes; nothing here can make a
 * recorded trace unreplayable, per the ticket's binding replay constraint.
 */
object TraceBudget {

    /**
     * Capacity of the recorder's buffering channel (task 5). Bounds the memory
     * a runaway producer can consume before [RecordingHandle.stop] ever runs,
     * independently of the byte-size contract below, which only applies once
     * draining begins. Deliberately much larger than any real recording window
     * is expected to need — a backstop, not the size contract itself.
     */
    const val CHANNEL_CAPACITY = 50_000

    /**
     * Max chars any single string leaf in an event's serialized payload may
     * carry before [truncateStringLeaves] cuts it. `Event` is a sealed
     * *interface* spread across ~24 files, each with its own free-text fields
     * (description, context, prompt, preview, ...); bounding leaf length
     * generically, from the serialized [kotlinx.serialization.json.JsonElement],
     * keeps every current and future field covered from one place.
     */
    const val MAX_STRING_FIELD_CHARS = 2_000

    /**
     * Max serialized bytes a single [TraceEvent.payload] should occupy after
     * truncation. A quarter of the 32 KiB canon projection budget established by
     * AMPR-262's `CanonWorkEntitiesTest` — an event payload wraps a
     * comparatively small, flat set of fields next to a full canon projection.
     */
    const val MAX_EVENT_BYTES = 8 * 1024

    /**
     * Max cumulative serialized bytes a [Trace]'s events may occupy. Events
     * beyond this bound are dropped (trailing, in emission order) and counted
     * in [Trace.droppedEventCount]. 512 KiB keeps a worst-case trace (every
     * event at [MAX_EVENT_BYTES]) to 64 events before drop kicks in, and stays
     * a comfortable SQLite `TEXT` value (see `Trace.sq`).
     */
    const val MAX_TRACE_BYTES = 512 * 1024
}
