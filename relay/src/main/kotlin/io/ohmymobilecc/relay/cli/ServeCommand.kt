package io.ohmymobilecc.relay.cli

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.websocket.WebSockets
import io.ohmymobilecc.core.protocol.WireMessage
import io.ohmymobilecc.relay.server.RelayServer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import io.ktor.server.application.install as installApp

/**
 * `relay-cli serve` subcommand. Starts a Ktor Netty engine bound to:
 *  - `--port N` (explicit flag), else
 *  - env `RELAY_PORT`, else
 *  - [DEFAULT_PORT]
 *
 * Prints `relay listening on :<actual-port>` to stdout once the engine
 * has resolved its connector (important when `--port 0` lets the OS
 * pick). Registers a [RelayProcessState] shutdown hook so tests (and
 * later, a real SIGINT handler) can drive a graceful stop.
 *
 * Inbound frames are routed into [RelayProcessState.approvalBridge] —
 * only `ApprovalResponded` is consumed today; other frames are ignored
 * until W2 introduces terminal / file routes.
 */
public object ServeCommand {
    public const val EXIT_OK: Int = 0
    public const val EXIT_ERROR: Int = 2

    public const val DEFAULT_PORT: Int = 48964
    private const val GRACE_MS: Long = 500L

    public fun run(
        argv: Array<String>,
        stdout: PrintStream,
        stderr: PrintStream,
        state: RelayProcessState,
    ): Int {
        val port =
            runCatching { parsePort(argv) }.getOrElse {
                stderr.println("relay-cli serve: ${it.message}")
                return EXIT_ERROR
            }

        val engine =
            embeddedServer(Netty, port = port) {
                installApp(WebSockets)
                RelayServer.install(
                    app = this,
                    registry = state.registry,
                    nonceCache = state.nonceCache,
                    clock = state.clock,
                    outbound = state.outboundFlow,
                    onInbound = { msg ->
                        if (msg is WireMessage.ApprovalResponded) {
                            state.approvalBridge.submitDecision(
                                approvalId = msg.approvalId,
                                decision = msg.decision,
                                customInput = msg.customInput,
                            )
                        }
                    },
                )
            }
        engine.start(wait = false)

        // A Channel(1) is used as a one-shot "stop" signal so the main
        // thread can block here without busy-waiting while tests drive
        // `state.signalShutdown()`.
        val stopSignal = Channel<Unit>(capacity = 1)
        state.registerShutdownHook {
            runCatching { stopSignal.trySend(Unit) }
            engine.stop(GRACE_MS, GRACE_MS)
        }

        val resolvedPort =
            runBlocking {
                engine.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        stdout.println("relay listening on :$resolvedPort")
        stdout.flush()

        // Block the caller until shutdown is signalled (via test hook or SIGINT).
        runBlocking { stopSignal.receive() }
        return EXIT_OK
    }

    private fun parsePort(argv: Array<String>): Int {
        var i = 0
        while (i < argv.size) {
            when (argv[i]) {
                "--port" -> {
                    val raw = argv.getOrNull(i + 1) ?: error("missing value for --port")
                    return raw.toIntOrNull() ?: error("--port must be an integer, got: $raw")
                }
                else -> error("unknown argument: ${argv[i]}")
            }
        }
        val env = System.getenv("RELAY_PORT")?.toIntOrNull()
        return env ?: DEFAULT_PORT
    }
}
