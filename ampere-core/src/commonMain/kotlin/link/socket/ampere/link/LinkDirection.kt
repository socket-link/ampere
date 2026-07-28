package link.socket.ampere.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which way data may flow over a [Link].
 *
 * **Links are directional endpoints, not sources.** APNS is a write-only sink;
 * a folder mount is read/write; Calendar is read-mostly. Modelling direction on
 * the Link is what makes "this Plug tried to Perceive through a push channel" a
 * resolution-time failure instead of a runtime surprise.
 */
@Serializable
enum class LinkDirection {

    @SerialName("read")
    READ,

    @SerialName("write")
    WRITE,

    @SerialName("read_write")
    READ_WRITE,
    ;

    val canRead: Boolean get() = this == READ || this == READ_WRITE

    val canWrite: Boolean get() = this == WRITE || this == READ_WRITE

    fun permits(operation: LinkOperation): Boolean = when (operation) {
        LinkOperation.PERCEIVE -> canRead
        LinkOperation.EXECUTE -> canWrite
    }

    /**
     * Whether a Link with this direction can stand in for one a requirement
     * asked for. `READ_WRITE` satisfies everything; `READ` satisfies only
     * `READ`.
     */
    fun satisfies(required: LinkDirection): Boolean =
        (!required.canRead || canRead) && (!required.canWrite || canWrite)
}

/**
 * The two halves of the chassis boundary, named as directions.
 *
 * Perceive pulls; Execute pushes. Push-shaped sources (HealthKit observers,
 * location updates, APNS delivery) are an adapter concern — the transport
 * buffers into the bus and the chassis presents buffered emissions as discrete
 * observations at the next Perceive.
 */
@Serializable
enum class LinkOperation {

    @SerialName("perceive")
    PERCEIVE,

    @SerialName("execute")
    EXECUTE,
}
