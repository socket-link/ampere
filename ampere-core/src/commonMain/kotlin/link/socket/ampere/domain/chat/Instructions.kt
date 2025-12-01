package link.socket.ampere.domain.chat

data class Instructions(
    val prompt: String,
) {
    fun build(): String = prompt
}
