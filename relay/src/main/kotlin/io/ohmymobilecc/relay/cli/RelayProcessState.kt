package io.ohmymobilecc.relay.cli

import io.ohmymobilecc.core.crypto.platformSecureRandom
import io.ohmymobilecc.core.protocol.WireMessage
import io.ohmymobilecc.relay.approval.ApprovalBridge
import io.ohmymobilecc.relay.approval.InMemoryApprovalStore
import io.ohmymobilecc.relay.pairing.ClockSeam
import io.ohmymobilecc.relay.pairing.InMemoryPubkeyRegistry
import io.ohmymobilecc.relay.pairing.NonceCache
import io.ohmymobilecc.relay.pairing.PairingService
import io.ohmymobilecc.relay.pairing.SystemClockSeam
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-JVM-process shared state for the `relay-cli` subcommands.
 *
 * Why a single instance? `pair` registers a pubkey into the same
 * [InMemoryPubkeyRegistry] that `serve` reads from when verifying
 * `ClientHello` — so both subcommands MUST see the same instance when
 * they run in-process (typical test setup). In production `pair` runs
 * as a separate short-lived process, so this coupling only matters for
 * tests + any future in-process integration glue; SqlDelight persistence
 * in W2.3 will lift the limitation.
 *
 * A [ServeCommand] instance registers a shutdown callback here so the
 * test can drive a `SIGINT`-equivalent graceful stop without killing
 * the JVM.
 */
public class RelayProcessState(
    public val clock: ClockSeam = SystemClockSeam,
    public val registry: InMemoryPubkeyRegistry = InMemoryPubkeyRegistry(),
    public val nonceCache: NonceCache = NonceCache(),
    public val pairingService: PairingService =
        PairingService(
            clock = clock,
            random = platformSecureRandom(),
            registry = registry,
        ),
    public val outbound: MutableSharedFlow<WireMessage> = MutableSharedFlow(extraBufferCapacity = OUTBOUND_BUFFER),
    public val approvalBridge: ApprovalBridge =
        ApprovalBridge(
            store = InMemoryApprovalStore(),
            outbound = outbound,
        ),
) {
    private val shutdownFlag = AtomicBoolean(false)
    private val shutdownHooks = mutableListOf<() -> Unit>()

    public val isShutdown: Boolean get() = shutdownFlag.get()

    public fun registerShutdownHook(hook: () -> Unit) {
        synchronized(shutdownHooks) { shutdownHooks += hook }
    }

    public fun signalShutdown() {
        if (shutdownFlag.compareAndSet(false, true)) {
            val snapshot = synchronized(shutdownHooks) { shutdownHooks.toList() }
            snapshot.forEach { runCatching { it() } }
        }
    }

    public val outboundFlow: kotlinx.coroutines.flow.SharedFlow<WireMessage> get() = outbound.asSharedFlow()

    public companion object {
        private const val OUTBOUND_BUFFER: Int = 64

        /** Build a new, isolated [RelayProcessState] — primarily for tests. */
        public fun fresh(): RelayProcessState = RelayProcessState()
    }
}
