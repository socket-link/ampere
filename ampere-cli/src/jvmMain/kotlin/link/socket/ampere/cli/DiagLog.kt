package link.socket.ampere.cli

import java.io.File
import java.io.FileWriter

private val diagFile: FileWriter by lazy {
    val file = File("/tmp/ampere_diag.log")
    FileWriter(file, false)
}

fun diagLog(msg: String) {
    try {
        synchronized(diagFile) {
            diagFile.write(msg)
            diagFile.write("\n")
            diagFile.flush()
        }
    } catch (_: Exception) {}
}
