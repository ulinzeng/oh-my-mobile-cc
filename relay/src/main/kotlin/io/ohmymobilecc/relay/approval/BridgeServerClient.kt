package io.ohmymobilecc.relay.approval

import io.ohmymobilecc.core.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Accept-loop UNIX-domain-socket server that forwards every inbound
 * one-shot connection to [bridge]`.requestApproval` and writes the
 * resulting [BridgeDecision] back as a single NDJSON line.
 *
 * Wire shape:
 *  `BridgeRequest JSON + "\n"` (client→server)
 *  `BridgeDecision JSON + "\n"` (server→client)
 *  both sides close immediately after.
 *
 * Intended for the `relay-cli approval-bridge` subprocess — the server
 * lives in the main relay for the lifetime of that process; the client
 * is spawned once per CC `PreToolUse` hook invocation.
 */
public class BridgeServer(
    private val socketPath: Path,
    private val bridge: ApprovalBridge,
    private val scope: CoroutineScope,
    private val json: kotlinx.serialization.json.Json = ProtocolJson.default,
) : AutoCloseable {
    private var serverChannel: ServerSocketChannel? = null
    private var acceptJob: Job? = null

    public fun start() {
        // Stale socket files survive a crashed relay; delete before
        // binding so we don't hit "Address already in use". We guard
        // by requiring the path not to be a directory — nobody should
        // be pointing us at a directory, but defense in depth.
        if (Files.exists(socketPath) && !Files.isDirectory(socketPath)) {
            Files.delete(socketPath)
        }
        Files.createDirectories(socketPath.parent)

        val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        ch.bind(UnixDomainSocketAddress.of(socketPath))
        serverChannel = ch

        // Restrict to owner-only perms if the FS supports it. A no-op
        // on FAT/tmpfs-without-posix, which is fine — UDS itself
        // provides UID-scoped trust boundary already.
        runCatching {
            Files.setPosixFilePermissions(
                socketPath,
                PosixFilePermissions.fromString("rw-------"),
            )
        } // ignore failure — filesystem doesn't support POSIX perms

        acceptJob =
            scope.launch(Dispatchers.IO) {
                while (isOpen(ch)) {
                    val client =
                        runCatching { ch.accept() }.getOrElse {
                            // Channel closed — break.
                            return@launch
                        }
                    launch(Dispatchers.IO) { serve(client) }
                }
            }
    }

    private suspend fun serve(client: SocketChannel) {
        client.use { c ->
            // IMPORTANT: don't wrap the reader in .use — closing the
            // reader closes the underlying channel stream, which
            // would leave the client waiting forever for our decision.
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(c), StandardCharsets.UTF_8))
            val reqLine = reader.readLine() ?: return
            val request = json.decodeFromString(BridgeRequest.serializer(), reqLine)
            val outcome = bridge.requestApproval(request)
            val decisionLine = json.encodeToString(BridgeDecision.serializer(), outcome.decision) + "\n"
            withContext(Dispatchers.IO) {
                val writer = OutputStreamWriter(Channels.newOutputStream(c), StandardCharsets.UTF_8)
                writer.write(decisionLine)
                writer.flush()
                // Channel closes via the outer `client.use`.
            }
        }
    }

    override fun close() {
        acceptJob?.cancel()
        runCatching { serverChannel?.close() }
        runCatching { Files.deleteIfExists(socketPath) }
    }

    private fun isOpen(ch: ServerSocketChannel): Boolean = ch.isOpen
}

/**
 * One-shot client used by the `relay-cli approval-bridge` subprocess.
 * Opens a UDS to [socketPath], writes the [BridgeRequest], reads one
 * [BridgeDecision], closes.
 */
public object BridgeClient {
    public suspend fun request(
        socketPath: Path,
        request: BridgeRequest,
        json: kotlinx.serialization.json.Json = ProtocolJson.default,
    ): BridgeDecision =
        withContext(Dispatchers.IO) {
            if (!Files.exists(socketPath)) {
                throw IOException("bridge socket not found: $socketPath")
            }
            val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
            ch.use { c ->
                c.connect(UnixDomainSocketAddress.of(socketPath))
                val reqLine = json.encodeToString(BridgeRequest.serializer(), request) + "\n"
                OutputStreamWriter(Channels.newOutputStream(c), StandardCharsets.UTF_8).let { w ->
                    w.write(reqLine)
                    w.flush()
                    // Half-close would be ideal; UDS on JDK supports it
                    // via shutdownOutput(). This signals EOF to the
                    // server's readLine() call.
                    c.shutdownOutput()
                }
                val respLine =
                    BufferedReader(InputStreamReader(Channels.newInputStream(c), StandardCharsets.UTF_8)).use { r ->
                        r.readLine()
                    } ?: throw IOException("bridge server closed connection without a decision")
                json.decodeFromString(BridgeDecision.serializer(), respLine)
            }
        }
}
