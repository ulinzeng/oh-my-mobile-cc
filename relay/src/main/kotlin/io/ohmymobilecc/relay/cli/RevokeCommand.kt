package io.ohmymobilecc.relay.cli

import io.ohmymobilecc.core.pairing.DeviceId
import java.io.PrintStream

/**
 * `relay-cli revoke <deviceId>` subcommand.
 *
 * Marks a paired device revoked in the in-memory
 * [io.ohmymobilecc.relay.pairing.InMemoryPubkeyRegistry]. Per
 * `openspec/specs/pairing/spec.md` §撤销, already-open WS sessions are
 * NOT forcibly closed — the revoke takes effect on the next `ClientHello`
 * from the revoked device.
 *
 * Exit: always 0 (missing id is a warning, not an error).
 */
public object RevokeCommand {
    public const val EXIT_OK: Int = 0
    public const val EXIT_ERROR: Int = 2

    // Guard-clause chain: 3 exit paths (missing arg / unknown id / revoked)
    // are each load-bearing and read clearer than nested when-blocks.
    @Suppress("ReturnCount")
    public fun run(
        argv: Array<String>,
        stdout: PrintStream,
        stderr: PrintStream,
        state: RelayProcessState,
    ): Int {
        val rawDeviceId =
            argv.firstOrNull() ?: run {
                stderr.println("relay-cli revoke: missing <deviceId>")
                return EXIT_ERROR
            }
        val id = DeviceId(rawDeviceId)
        val existing = state.registry.find(id)
        if (existing == null) {
            stdout.println("warning: deviceId=${id.raw} is not paired; no change")
            return EXIT_OK
        }
        val now = state.clock.nowMs()
        state.registry.revoke(id, now)
        stdout.println("revoked deviceId=${id.raw} at=$now")
        return EXIT_OK
    }
}
