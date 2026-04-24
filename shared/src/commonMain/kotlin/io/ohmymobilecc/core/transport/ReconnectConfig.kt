package io.ohmymobilecc.core.transport

/**
 * Tunable parameters for [ReconnectingTransportPort]'s exponential-backoff
 * retry loop.
 *
 * Defaults are conservative for mobile networks: start at 1 s, cap at 30 s,
 * double each attempt, add ±20 % jitter. [maxRetries] defaults to unlimited
 * so the client retries until the user explicitly closes the port.
 */
public data class ReconnectConfig(
    /** Base delay for the first retry, in milliseconds. */
    public val initialDelayMs: Long = 1_000L,
    /** Upper bound for the computed delay, in milliseconds. */
    public val maxDelayMs: Long = 30_000L,
    /** Multiplicative factor applied to the delay on each successive attempt. */
    public val multiplier: Double = 2.0,
    /** Fraction of the computed delay added/subtracted as uniform jitter (0.0–0.5). */
    public val jitterFraction: Double = 0.2,
    /** Stop retrying after this many consecutive failures. [Int.MAX_VALUE] = unlimited. */
    public val maxRetries: Int = Int.MAX_VALUE,
)
