package io.ohmymobilecc.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ohmymobilecc.core.crypto.Base64Url
import io.ohmymobilecc.core.crypto.Ed25519
import io.ohmymobilecc.core.crypto.platformSecureRandom
import io.ohmymobilecc.core.pairing.HelloCodec
import io.ohmymobilecc.core.protocol.ProtocolJson
import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * JVM actual of [TransportPort], backed by a Ktor [HttpClient] with the
 * CIO engine + websockets plugin (wired in `shared/build.gradle.kts`).
 *
 * The client assumes [httpClient] already has `WebSockets` installed;
 * `KtorRelayClientTest`'s `defaultClientForTest` builder demonstrates the
 * expected configuration.
 *
 * `clock` is injected so tests (and later, the session skew-correction
 * layer) can override it. [nonceBytes] is fixed at 16 bytes per
 * `openspec/specs/pairing/spec.md` §Replay 防护.
 */
public class KtorRelayClient(
    private val httpClient: HttpClient,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: io.ohmymobilecc.core.crypto.RandomSource = platformSecureRandom(),
) : TransportPort {
    override suspend fun connect(
        endpoint: TransportEndpoint,
        identity: DeviceIdentity,
    ): Result<TransportSession> =
        runCatching {
            val session = openSocket(endpoint)
            handshake(session, endpoint, identity)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { t -> Result.failure(mapError(t)) },
        )

    override suspend fun shutdown() {
        httpClient.close()
    }

    private suspend fun openSocket(endpoint: TransportEndpoint): DefaultClientWebSocketSession =
        httpClient.webSocketSession(
            method = HttpMethod.Get,
            host = endpoint.host,
            port = endpoint.port,
            path = endpoint.pathPrefix,
        )

    private suspend fun handshake(
        session: DefaultClientWebSocketSession,
        endpoint: TransportEndpoint,
        identity: DeviceIdentity,
    ): TransportSession {
        sendClientHello(session, endpoint, identity)
        return when (val firstFrame = session.incoming.receive()) {
            is Frame.Text -> decodeHandshakeReply(session, firstFrame)
            else -> {
                session.close(CloseReason(CLOSE_POLICY, "pv"))
                throw RelayError.ProtocolViolation("non-text first frame")
            }
        }
    }

    private suspend fun sendClientHello(
        session: DefaultClientWebSocketSession,
        endpoint: TransportEndpoint,
        identity: DeviceIdentity,
    ) {
        val now = clock()
        val nonce = Base64Url.encode(random.nextBytes(NONCE_BYTES))
        val canonical =
            HelloCodec
                .canonicalSigningInput(endpoint.sessionId, now, nonce)
                .encodeToByteArray()
        val sig = Ed25519.sign(identity.secretKey, canonical)
        val hello =
            WireMessage.ClientHello(
                deviceId = identity.deviceId.raw,
                sessionId = endpoint.sessionId,
                timestampMs = now,
                nonce = nonce,
                sig = Base64Url.encode(sig),
            )
        session.send(Frame.Text(ProtocolJson.default.encodeToString<WireMessage>(hello)))
    }

    private suspend fun decodeHandshakeReply(
        session: DefaultClientWebSocketSession,
        frame: Frame.Text,
    ): TransportSession {
        val msg =
            runCatching { ProtocolJson.default.decodeFromString<WireMessage>(frame.readText()) }
                .getOrNull()
                ?: run {
                    session.close(CloseReason(CLOSE_POLICY, "pv"))
                    throw RelayError.ProtocolViolation("unparseable first reply")
                }
        return when (msg) {
            is WireMessage.HelloOk -> KtorTransportSession(session)
            is WireMessage.HelloErr -> {
                session.close(CloseReason(CLOSE_POLICY, msg.reason))
                throw RelayError.Rejected(msg.reason)
            }
            else -> {
                session.close(CloseReason(CLOSE_POLICY, "pv"))
                throw RelayError.ProtocolViolation("expected hello.ok/hello.err")
            }
        }
    }

    private fun mapError(throwable: Throwable): Throwable =
        when (throwable) {
            is RelayError -> throwable
            is ClosedReceiveChannelException -> RelayError.ProtocolViolation("relay closed before hello")
            else -> RelayError.Network(throwable)
        }

    private companion object {
        const val NONCE_BYTES: Int = 16
        const val CLOSE_POLICY: Short = 1008
    }
}

/**
 * Post-handshake session wrapping an open [DefaultClientWebSocketSession].
 * Spawns an internal pump that converts inbound `Frame.Text` into
 * [WireMessage] emissions on a [MutableSharedFlow]. Dropped frames
 * (binary, malformed) match the server-side "post-hello unparseable is
 * non-fatal" rule from `openspec/specs/protocol/spec.md` § 传输语义.
 */
private class KtorTransportSession(
    private val session: DefaultClientWebSocketSession,
) : TransportSession {
    // replay=1 so a push that arrives before the first `collect` isn't lost —
    // critical for the handshake→first-push race where the relay may emit
    // ApprovalRequested before callers subscribe via `incoming.first()`.
    private val _incoming =
        MutableSharedFlow<WireMessage>(
            replay = 1,
            extraBufferCapacity = BUFFER_SIZE,
        )

    init {
        session.launch {
            try {
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    val decoded =
                        runCatching {
                            ProtocolJson.default.decodeFromString<WireMessage>(frame.readText())
                        }.getOrNull() ?: continue
                    _incoming.emit(decoded)
                }
            } catch (_: ClosedReceiveChannelException) {
                // remote closed — normal termination
            }
        }
    }

    override val incoming: Flow<WireMessage> = _incoming.asSharedFlow()

    override suspend fun send(message: WireMessage) {
        session.send(Frame.Text(ProtocolJson.default.encodeToString<WireMessage>(message)))
    }

    override suspend fun close() {
        session.close(CloseReason(CLOSE_NORMAL, "client close"))
    }

    private companion object {
        const val BUFFER_SIZE: Int = 64
        const val CLOSE_NORMAL: Short = 1000
    }
}
