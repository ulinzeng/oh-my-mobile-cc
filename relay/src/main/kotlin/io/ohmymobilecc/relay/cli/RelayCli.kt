package io.ohmymobilecc.relay.cli

import java.io.InputStream
import java.io.PrintStream

/**
 * Entry-point router for the `relay-cli` shadow-jar binary. The
 * application main (see `relay/src/main/kotlin/io/ohmymobilecc/relay/Main.kt`)
 * calls [dispatch] with the raw argv. Subcommands:
 *
 *  - `approval-bridge` — [ApprovalBridgeCommand] (spawned by CC's `PreToolUse` hook)
 *  - `pair` — [PairCommand] (emit 6-digit code, wait for mobile redeem)
 *  - `revoke <deviceId>` — [RevokeCommand]
 *  - `serve` — [ServeCommand] (host the relay WS on `/ws`)
 *
 * `pair`, `revoke`, `serve` share a single process-scoped
 * [RelayProcessState] instance so their in-memory registry / nonce cache
 * / approval bridge stay consistent across in-process tests. In prod
 * they run as independent short-lived processes, but the state
 * initialization is identical.
 */
public object RelayCli {
    // A singleton for the real process; tests build their own fresh instances.
    private val state: RelayProcessState by lazy { RelayProcessState() }

    public fun dispatch(
        argv: Array<String>,
        stdin: InputStream = System.`in`,
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err,
        stateOverride: RelayProcessState? = null,
    ): Int {
        val s = stateOverride ?: state
        val subcommand = argv.firstOrNull()
        val rest = if (argv.isEmpty()) emptyArray() else argv.copyOfRange(1, argv.size)
        return when (subcommand) {
            "approval-bridge" ->
                ApprovalBridgeCommand.run(argv = rest, stdin = stdin, stdout = stdout, stderr = stderr)
            "pair" -> PairCommand.run(argv = rest, stdout = stdout, stderr = stderr, state = s)
            "revoke" -> RevokeCommand.run(argv = rest, stdout = stdout, stderr = stderr, state = s)
            "serve" -> ServeCommand.run(argv = rest, stdout = stdout, stderr = stderr, state = s)
            null, "" -> {
                stdout.println(
                    "oh-my-mobile-cc relay — subcommands: approval-bridge, pair, revoke, serve",
                )
                0
            }
            else -> {
                stderr.println("relay-cli: unknown subcommand: $subcommand")
                ApprovalBridgeCommand.EXIT_ERROR
            }
        }
    }
}
