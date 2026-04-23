package io.ohmymobilecc.relay.cli

import java.io.InputStream
import java.io.PrintStream

/**
 * Entry-point router for the `relay-cli` shadow-jar binary. The
 * application main (see `relay/src/main/kotlin/io/ohmymobilecc/relay/Main.kt`)
 * calls [dispatch] with the raw argv. Subcommand currently recognized:
 *
 *  - `approval-bridge` — run [ApprovalBridgeCommand] (spawned by CC's
 *    `PreToolUse` hook per tool-use request)
 *
 * The main relay server itself will be a second subcommand in W1.5
 * (Ktor WebSocket). For now, the absence of `approval-bridge` prints
 * a placeholder notice and exits 0 to preserve the W0 behavior.
 */
public object RelayCli {
    public fun dispatch(
        argv: Array<String>,
        stdin: InputStream = System.`in`,
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err,
    ): Int =
        when (argv.firstOrNull()) {
            "approval-bridge" ->
                ApprovalBridgeCommand.run(
                    argv = argv.copyOfRange(1, argv.size),
                    stdin = stdin,
                    stdout = stdout,
                    stderr = stderr,
                )
            null, "" -> {
                stdout.println("oh-my-mobile-cc relay — W1.4 placeholder. subcommands: approval-bridge")
                0
            }
            else -> {
                stderr.println("relay-cli: unknown subcommand: ${argv.first()}")
                ApprovalBridgeCommand.EXIT_ERROR
            }
        }
}
