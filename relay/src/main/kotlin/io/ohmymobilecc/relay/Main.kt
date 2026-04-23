package io.ohmymobilecc.relay

import io.ohmymobilecc.relay.cli.RelayCli
import kotlin.system.exitProcess

/**
 * Entry point for the relay shadow jar. Delegates to [RelayCli] which
 * dispatches based on argv subcommand (`approval-bridge` today; the
 * main Ktor server lands in W1.5).
 */
fun main(args: Array<String>) {
    exitProcess(RelayCli.dispatch(args))
}
