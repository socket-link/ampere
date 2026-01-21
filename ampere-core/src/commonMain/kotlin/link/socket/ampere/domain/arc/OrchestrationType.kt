package link.socket.ampere.domain.arc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OrchestrationType {
    @SerialName("sequential")
    SEQUENTIAL,

    @SerialName("parallel")
    PARALLEL,
}
