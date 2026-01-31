package link.socket.ampere.util

import kotlin.random.Random

actual fun randomUUID(): String {
    // WasmJS uses manual UUID v4 generation
    val chars = "0123456789abcdef"
    return buildString {
        repeat(8) { append(chars[Random.nextInt(16)]) }
        append('-')
        repeat(4) { append(chars[Random.nextInt(16)]) }
        append('-')
        append('4') // Version 4
        repeat(3) { append(chars[Random.nextInt(16)]) }
        append('-')
        append(chars[(Random.nextInt(4) + 8)]) // Variant
        repeat(3) { append(chars[Random.nextInt(16)]) }
        append('-')
        repeat(12) { append(chars[Random.nextInt(16)]) }
    }
}
