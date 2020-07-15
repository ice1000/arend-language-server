package org.ice1000.arend.lsp

import org.arend.ext.module.ModulePath
import org.arend.frontend.library.FileLoadableHeaderLibrary
import org.arend.util.FileUtils
import org.eclipse.lsp4j.MessageType
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Parse a possibly-percent-encoded URI string.
 * Decoding is necessary since some language clients
 * (including VSCode) invalidly percent-encode colons.
 */
fun parseURI(uri: String): URI = URI.create(runCatching {
  URLDecoder.decode(uri, StandardCharsets.UTF_8.toString())
      .replace(" ", "%20")
}.getOrDefault(uri))

fun String.partitionAroundLast(separator: String): Pair<String, String> = lastIndexOf(separator)
    .let { substring(0, it) to substring(it, length) }

fun describeURI(uri: String): String = describeURI(parseURI(uri))

fun describeURI(uri: URI): String = uri.toString()

/**
 * Starts a TCP server socket. Blocks until the first
 * client has connected, then returns a pair of IO streams.
 */
fun tcpStartServer(port: Int) = ServerSocket(port)
    .accept()
    .let { it.inputStream to it.outputStream }

fun basePath(inTests: Boolean, lib: FileLoadableHeaderLibrary) =
    if (inTests) lib.testBasePath else lib.sourceBasePath

fun ArendServices.groupOf(uri: String) = describe(uri)?.let { (lib, modulePath, inTests) ->
  lib.getModuleGroup(modulePath, inTests)
}

data class Description(
    val lib: FileLoadableHeaderLibrary,
    val modulePath: ModulePath,
    val inTests: Boolean,
    val path: Path
)

fun ArendServices.describe(uri: String): Description? {
  val path = Paths.get(parseURI(uri))
  val (lib, inTests) = currentLibrary(path) ?: return null
  val relative = basePath(inTests, lib).relativize(path)
  val modulePath = FileUtils.modulePath(relative, FileUtils.EXTENSION)
  return Description(lib, modulePath, inTests, path)
}

class ExitingInputStream(private val delegate: InputStream): InputStream() {
  override fun read(): Int = exitIfNegative { delegate.read() }
  override fun read(b: ByteArray): Int = exitIfNegative { delegate.read(b) }
  override fun read(b: ByteArray, off: Int, len: Int): Int = exitIfNegative { delegate.read(b, off, len) }

  private inline fun exitIfNegative(call: () -> Int): Int {
    val result = call()
    return if (result < 0) exitProcess(0) else result
  }
}

/**
 * Starts a TCP client socket and connects to the client at
 * the specified address, then returns a pair of IO streams.
 */
fun tcpConnectToClient(host: String, port: Int) =
    Socket(host, port).let { it.inputStream to it.outputStream }

object Logger {
  private var logging: ((String, MessageType) -> Unit)? = null
  fun initialize(logging: (String, MessageType) -> Unit) {
    synchronized(logging) { this.logging = logging }
  }

  fun i(msg: String) = log(msg, MessageType.Info)
  fun e(msg: String) = log(msg, MessageType.Error)
  fun w(msg: String) = log(msg, MessageType.Warning)
  fun log(msg: String, type: MessageType = MessageType.Log) {
    logging?.let { it(msg, type) }
  }
}
