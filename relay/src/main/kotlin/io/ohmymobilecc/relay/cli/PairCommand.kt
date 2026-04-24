package io.ohmymobilecc.relay.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.PrintStream

/**
 * `relay-cli pair` subcommand. Emits a fresh 6-digit pairing code on
 * stdout, then blocks until some paired-client process redeems it via
 * [RelayProcessState.pairingService] (which registers the pubkey in the
 * same [io.ohmymobilecc.relay.pairing.InMemoryPubkeyRegistry] that
 * `serve` reads).
 *
 * Exits 0 on successful redeem, [EXIT_ERROR] + `pairing timeout` on
 * stdout if no one redeems within [timeoutMs] (default 5 min per spec).
 */
public object PairCommand {
    public const val EXIT_OK: Int = 0
    public const val EXIT_ERROR: Int = 2

    public const val DEFAULT_TIMEOUT_MS: Long = 5L * 60L * 1000L

    public fun run(
        argv: Array<String>,
        stdout: PrintStream,
        stderr: PrintStream,
        state: RelayProcessState,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Int {
        if (argv.isNotEmpty()) {
            stderr.println("relay-cli pair: unexpected argument: ${argv.first()}")
            return EXIT_ERROR
        }
        val code = state.pairingService.issueCode()
        stdout.println(code.digits)
        stdout.flush()

        val redeemed =
            runBlocking {
                withTimeoutOrNull(timeoutMs) { awaitRedeem(state, code.digits) }
            }
        return if (redeemed == true) {
            EXIT_OK
        } else {
            stdout.println("pairing timeout")
            EXIT_ERROR
        }
    }

    /**
     * Poll [state.pairingService.peekIssued] until the code has been consumed
     * via `redeem`. The consuming call atomically removes the code from the
     * internal map, so the watcher sees `peekIssued(digits) == false`.
     *
     * Returns `true` when consumed (success), never returns `false` —
     * `withTimeoutOrNull` cancels this coroutine via its parent scope when
     * the budget is exhausted.
     */
    private suspend fun awaitRedeem(
        state: RelayProcessState,
        digits: String,
    ): Boolean =
        withContext(Dispatchers.Default) {
            while (state.pairingService.peekIssued(digits)) {
                delay(POLL_MS)
            }
            true
        }

    private const val POLL_MS: Long = 25L
}
