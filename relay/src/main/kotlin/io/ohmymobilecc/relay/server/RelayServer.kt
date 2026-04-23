package io.ohmymobilecc.relay.server

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ohmymobilecc.core.protocol.ProtocolJson
import io.ohmymobilecc.core.protocol.WireMessage
import io.ohmymobilecc.relay.pairing.ClientHelloVerifier
import io.ohmymobilecc.relay.pairing.ClockSeam
import io.ohmymobilecc.relay.pairing.NonceCache
import io.ohmymobilecc.relay.pairing.PubkeyRegistry
import io.ohmymobilecc.relay.pairing.VerifyResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * Dependency bundle for [RelayServer.install], sized to keep detekt's
 * `LongParameterList` rule quiet and to mirror the shape that will be
 * wired from `ServeCommand` once `relay-cli serve` lands in Task 3.
 */
public data class RelayServerConfig(
    val registry: PubkeyRegistry,
    val nonceCache: NonceCache,
    val clock: ClockSeam,
    val outbound: SharedFlow<WireMessage>,
    val onInbound: suspend (WireMessage) -> Unit,
    val connections: SingleConnectionRegistry = SingleConnectionRegistry(),
)

/**
 * Relay's WebSocket entry point. Installs a single `/ws` route on the
 * caller-supplied Ktor [Application]; `ServerWS` plugin **must already be
 * installed** by the caller (see `RelayServerTest`, `ServeCommand`).
 *
 * Handshake flow (one WS connection = one session):
 *
 * 1. Await first [Frame.Text] — anything else (binary, close, malformed
 *    JSON) → `HelloErr(malformed)` close 1007.
 * 2. Decode to [WireMessage]. Non-[WireMessage.ClientHello] → `HelloErr(expected-hello)` close 1008.
 * 3. Delegate to [ClientHelloVerifier]. Err → `HelloErr(reason)` close 1008.
 * 4. Claim slot via [SingleConnectionRegistry]. Fail → `HelloErr(duplicate-session)` close 1013.
 * 5. Reply `HelloOk(serverTimeMs = clock.nowMs(), protocolVersion = 1)`.
 * 6. Start outbound pump (fan-out bus → WS) and drive inbound loop.
 * 7. On disconnect (either side): release slot.
 *
 * `outbound` is a module-wide fan-out bus. To keep session-specific
 * filtering out of scope for W1.5, every active WS receives every
 * emission — the receiving client ignores mismatched sessionIds.
 */
public object RelayServer {
    private const val PROTOCOL_VERSION: Int = 1
    private const val CLOSE_MALFORMED: Short = 1007
    private const val CLOSE_POLICY: Short = 1008
    private const val CLOSE_TRY_AGAIN: Short = 1013

    private val tokenSeq = AtomicLong(0L)

    public fun install(
        app: Application,
        config: RelayServerConfig,
    ) {
        val verifier = ClientHelloVerifier(config.registry, config.nonceCache, config.clock)
        val json = ProtocolJson.default

        app.routing {
            webSocket("/ws") { handle(this, json, verifier, config) }
        }
    }

    /**
     * Five-arg overload kept for tests + downstream callers that prefer
     * positional arguments. Forwards to the [RelayServerConfig] form.
     */
    @Suppress("LongParameterList")
    public fun install(
        app: Application,
        registry: PubkeyRegistry,
        nonceCache: NonceCache,
        clock: ClockSeam,
        outbound: SharedFlow<WireMessage>,
        onInbound: suspend (WireMessage) -> Unit,
    ): Unit =
        install(
            app,
            RelayServerConfig(
                registry = registry,
                nonceCache = nonceCache,
                clock = clock,
                outbound = outbound,
                onInbound = onInbound,
            ),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun handle(
        session: DefaultWebSocketSession,
        json: Json,
        verifier: ClientHelloVerifier,
        config: RelayServerConfig,
    ) {
        val hello = session.awaitHello(json, verifier) ?: return
        val token = tokenSeq.incrementAndGet()
        if (!config.connections.claim(hello.sessionId, token)) {
            session.sendWire(json, WireMessage.HelloErr("duplicate-session"))
            session.close(CloseReason(CLOSE_TRY_AGAIN, "duplicate-session"))
            return
        }

        try {
            session.sendWire(
                json,
                WireMessage.HelloOk(
                    serverTimeMs = config.clock.nowMs(),
                    protocolVersion = PROTOCOL_VERSION,
                ),
            )

            val pump =
                session.launch {
                    config.outbound.collect { msg -> session.sendWire(json, msg) }
                }

            try {
                session.pumpInbound(json, config.onInbound)
            } finally {
                pump.cancel()
            }
        } finally {
            config.connections.release(hello.sessionId, token)
        }
    }

    /**
     * Validate the first frame. Either a verified [WireMessage.ClientHello]
     * or the `(reason, closeCode)` needed to reject.
     */
    private sealed interface HelloStage {
        data class Accepted(
            val hello: WireMessage.ClientHello,
        ) : HelloStage

        data class Rejected(
            val reason: String,
            val closeCode: Short,
        ) : HelloStage
    }

    // Guard-clause chain: one `return Rejected(reason)` per validation stage reads
    // clearer than nesting — same pattern/suppression as ClientHelloVerifier.verify.
    @Suppress("ReturnCount")
    private fun classifyFirstFrame(
        json: Json,
        verifier: ClientHelloVerifier,
        frame: Frame?,
    ): HelloStage {
        if (frame !is Frame.Text) {
            return HelloStage.Rejected("malformed", CLOSE_MALFORMED)
        }
        val parsed =
            runCatching { json.decodeFromString<WireMessage>(frame.readText()) }.getOrNull()
                ?: return HelloStage.Rejected("malformed", CLOSE_MALFORMED)
        val hello =
            parsed as? WireMessage.ClientHello
                ?: return HelloStage.Rejected("expected-hello", CLOSE_POLICY)
        return when (val verdict = verifier.verify(hello)) {
            is VerifyResult.Err -> HelloStage.Rejected(verdict.reason, CLOSE_POLICY)
            is VerifyResult.Ok -> HelloStage.Accepted(hello)
        }
    }

    /**
     * Consume the first frame and run it through [classifyFirstFrame].
     * Returns the verified [WireMessage.ClientHello] on success or `null`
     * after having already sent `HelloErr` + closed the WS.
     */
    private suspend fun DefaultWebSocketSession.awaitHello(
        json: Json,
        verifier: ClientHelloVerifier,
    ): WireMessage.ClientHello? {
        val firstFrame = runCatching { incoming.receive() }.getOrNull()
        return when (val stage = classifyFirstFrame(json, verifier, firstFrame)) {
            is HelloStage.Accepted -> stage.hello
            is HelloStage.Rejected -> {
                sendWire(json, WireMessage.HelloErr(stage.reason))
                close(CloseReason(stage.closeCode, stage.reason))
                null
            }
        }
    }

    /**
     * Post-handshake inbound loop. Unparseable frames are **warn + skip**
     * (non-fatal per `openspec/specs/protocol/spec.md` § 传输语义); binary
     * frames are ignored. The loop terminates when the remote closes.
     */
    private suspend fun DefaultWebSocketSession.pumpInbound(
        json: Json,
        onInbound: suspend (WireMessage) -> Unit,
    ) {
        try {
            for (frame in incoming) {
                val msg = decodeTextFrame(json, frame) ?: continue
                onInbound(msg)
            }
        } catch (_: ClosedReceiveChannelException) {
            // remote closed — normal termination
        }
    }

    private fun decodeTextFrame(
        json: Json,
        frame: Frame,
    ): WireMessage? {
        if (frame !is Frame.Text) return null
        return runCatching { json.decodeFromString<WireMessage>(frame.readText()) }.getOrNull()
    }

    private suspend fun WebSocketSession.sendWire(
        json: Json,
        msg: WireMessage,
    ) {
        try {
            send(Frame.Text(json.encodeToString<WireMessage>(msg)))
        } catch (_: SerializationException) {
            // Serialization of known WireMessage subtypes is total — but keep
            // this guard so a future `Unknown` payload can't take the pipe down.
        }
    }
}
