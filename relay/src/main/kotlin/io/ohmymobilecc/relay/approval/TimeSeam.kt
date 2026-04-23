package io.ohmymobilecc.relay.approval

/**
 * Seams for wall-clock time and suspending delay, injected into
 * [ApprovalBridge] so tests can drive the 10-minute timeout in
 * virtual time via `kotlinx.coroutines.test.TestCoroutineScheduler`.
 *
 * Production wiring uses [SystemTimeSeam], which reads
 * `System.currentTimeMillis()` for [now] and delegates to
 * `kotlinx.coroutines.delay` for [delay].
 */
public interface TimeSeam {
    public fun now(): Long

    public suspend fun delay(millis: Long)
}

public object SystemTimeSeam : TimeSeam {
    override fun now(): Long = System.currentTimeMillis()

    override suspend fun delay(millis: Long) {
        kotlinx.coroutines.delay(millis)
    }
}
