package io.ohmymobilecc.relay.pairing

import kotlinx.datetime.Clock

/**
 * Virtual-clock seam for pairing-flow time. Scoped to this package so it
 * doesn't collide with [io.ohmymobilecc.relay.approval.TimeSeam] from W1.4;
 * the two could later be unified once both capabilities are stable.
 */
public interface ClockSeam {
    public fun nowMs(): Long
}

public object SystemClockSeam : ClockSeam {
    override fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
