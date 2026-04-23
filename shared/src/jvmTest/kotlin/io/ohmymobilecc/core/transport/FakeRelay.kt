package io.ohmymobilecc.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ohmymobilecc.core.protocol.ProtocolJson
import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.io.Closeable

/**
 * Behavior selector for [startFakeRelay]. Each mode scripts a distinct
 * relay response so `KtorRelayClientTest` can assert each `TransportPort`
 * contract case without a shared state machine.
 */
internal enum class FakeRelayMode {
    /** Reply `HelloOk`, then push an `ApprovalRequested`, then wait for close. */
    HAPPY,

    /** Reply `HelloErr(revoked)` and close 1008. */
    REVOKED,

    /** Before `HelloOk`: push an `ApprovalRequested` on first text frame. */
    PROTOCOL_VIOLATION,
}

/**
 * Running fake relay — a real Ktor Netty engine on a free port. Call
 * [stop] (or use via [Closeable.use]) to shut it down deterministically
 * after the test.
 */
internal class FakeRelay(
    private val engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    val port: Int,
) : Closeable {
    fun stop() {
        engine.stop(GRACE_MS, GRACE_MS)
    }

    override fun close(): Unit = stop()

    private companion object {
        const val GRACE_MS: Long = 500L
    }
}

internal fun startFakeRelay(mode: FakeRelayMode): FakeRelay {
    val json = ProtocolJson.default
    val engine =
        embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws") {
                    when (mode) {
                        FakeRelayMode.HAPPY -> handleHappy(this, json)
                        FakeRelayMode.REVOKED -> handleRevoked(this, json)
                        FakeRelayMode.PROTOCOL_VIOLATION -> handleProtocolViolation(this, json)
                    }
                }
            }
        }
    engine.start(wait = false)
    val port = runBlocking { engine.engine.resolvedConnectors().first().port }
    return FakeRelay(engine, port)
}

private suspend fun handleHappy(
    session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    json: kotlinx.serialization.json.Json,
) {
    // Eat the ClientHello (any content is fine — we don't verify in this fake).
    val first = session.incoming.receive()
    require(first is Frame.Text) { "happy-path fake expects text hello" }
    json.decodeFromString<WireMessage>(first.readText())

    session.send(
        Frame.Text(
            json.encodeToString<WireMessage>(
                WireMessage.HelloOk(serverTimeMs = 1_700_000_000_000L, protocolVersion = 1),
            ),
        ),
    )
    session.send(
        Frame.Text(
            json.encodeToString<WireMessage>(
                WireMessage.ApprovalRequested(
                    approvalId = "a1",
                    sessionId = "S1",
                    tool = "Bash",
                    input = JsonObject(emptyMap()),
                    proposedAt = 0L,
                ),
            ),
        ),
    )
    // Hold the socket open until the client closes it.
    for (frame in session.incoming) {
        if (frame is Frame.Close) break
    }
}

private suspend fun handleRevoked(
    session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    json: kotlinx.serialization.json.Json,
) {
    // Wait for hello then respond with HelloErr(revoked) and close 1008.
    session.incoming.receive()
    session.send(Frame.Text(json.encodeToString<WireMessage>(WireMessage.HelloErr("revoked"))))
    session.close(CloseReason(CLOSE_POLICY, "revoked"))
}

private suspend fun handleProtocolViolation(
    session: io.ktor.server.websocket.DefaultWebSocketServerSession,
    json: kotlinx.serialization.json.Json,
) {
    // Push a non-hello frame *before* the client would expect HelloOk.
    session.send(
        Frame.Text(
            json.encodeToString<WireMessage>(
                WireMessage.ApprovalRequested(
                    approvalId = "pre-hello",
                    sessionId = "pv",
                    tool = "Bash",
                    input = JsonObject(emptyMap()),
                    proposedAt = 0L,
                ),
            ),
        ),
    )
    delay(PROTOCOL_VIOLATION_HOLD_MS)
    session.close(CloseReason(CLOSE_POLICY, "pv"))
}

/**
 * Shared HttpClient factory for the JVM transport contract test. CIO engine
 * matches `jvmMain` dependency so the runtime shape is identical.
 */
internal fun defaultClientForTest(): HttpClient =
    HttpClient(CIO) {
        install(ClientWebSockets)
    }

private const val CLOSE_POLICY: Short = 1008
private const val PROTOCOL_VIOLATION_HOLD_MS: Long = 100L
