package io.ohmymobilecc.core.transport

import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Resilient wrapper around a [TransportPort] that adds automatic reconnection
 * with exponential backoff, monotonic sequence tracking, and replay-aware
 * state machine transitions.
 *
 * ## Usage
 * ```kotlin
 * val port = ReconnectingTransportPort(delegate, endpoint, identity)
 * port.start(viewModelScope)
 * port.events.collect { event -> /* handle */ }
 * port.connectionState.collect { state -> /* update UI */ }
 * port.close()
 * ```
 *
 * ## Thread-safety
 * [connectionState] and [events] are backed by coroutine-safe
 * [StateFlow] / [SharedFlow]. [lastSeenSeq] uses [MutableStateFlow] for
 * thread-safe reads from any thread.
 */
public class ReconnectingTransportPort(
    private val delegate: TransportPort,
    private val endpoint: TransportEndpoint,
    private val identity: DeviceIdentity,
    private val config: ReconnectConfig = ReconnectConfig(),
    private val random: Random = Random.Default,
    initialLastSeq: Long? = null,
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** Observable connection state. Never replays stale values — always reflects the current state. */
    public val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Stream of high-level events. Subscribers joining late do NOT receive old events. */
    public val events: Flow<IncomingEvent> = _events.asSharedFlow()

    private val lastSeenSeqState = MutableStateFlow(initialLastSeq ?: 0L)

    /** Highest event seq consumed so far. Safe to read from any thread. */
    public fun lastSeenSeq(): Long = lastSeenSeqState.value

    private var connectJob: Job? = null

    /**
     * Start the connect-loop in [scope]. The loop runs until cancelled via
     * [close], a FATAL rejection is received, or `maxRetries` is exhausted.
     */
    public fun start(scope: CoroutineScope) {
        connectJob = scope.launch { connectLoop() }
    }

    /** Cancel the connect-loop and transition to [ConnectionState.Disconnected]. Idempotent. */
    public suspend fun close() {
        connectJob?.cancelAndJoin()
        connectJob = null
        _connectionState.value = ConnectionState.Disconnected
    }

    // -- internal machinery --------------------------------------------------

    private suspend fun connectLoop() {
        var attempt = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (attempt == 0) {
                _connectionState.value = ConnectionState.Connecting
            } else {
                val nextDelay = nextDelay(attempt)
                _connectionState.value = ConnectionState.Reconnecting(attempt, nextDelay)
                delay(nextDelay)
                _connectionState.value = ConnectionState.Connecting
            }

            when (val connectResult = tryConnect()) {
                is ConnectOutcome.Success -> {
                    val session = connectResult.session
                    val helloOk = session.helloOk

                    // Determine replay state
                    val lastSeq = lastSeenSeqState.value
                    val hasGap = lastSeq > 0L && helloOk.oldestSeq > lastSeq + 1
                    val expectReplay = lastSeq > 0L && helloOk.latestSeq > lastSeq

                    if (expectReplay) {
                        _connectionState.value =
                            ConnectionState.Replaying(
                                ReplayProgress(
                                    oldestSeq = helloOk.oldestSeq,
                                    latestSeq = helloOk.latestSeq,
                                    lastEventSeq = lastSeq,
                                    hasGap = hasGap,
                                ),
                            )
                    } else {
                        _connectionState.value = ConnectionState.Connected(helloOk.serverTimeMs)
                        _events.emit(IncomingEvent.ConnectionEstablished)
                    }

                    // Reset attempt on successful connect
                    attempt = 0

                    val sessionResult = handleSession(session, hasGap)
                    when (sessionResult) {
                        SessionOutcome.FATAL -> {
                            _connectionState.value = ConnectionState.Disconnected
                            return
                        }
                        SessionOutcome.SESSION_ENDED -> {
                            _connectionState.value = ConnectionState.Disconnected
                            return
                        }
                        SessionOutcome.DISCONNECTED -> {
                            attempt = 1
                            _events.emit(IncomingEvent.ConnectionLost("connection lost"))
                            continue
                        }
                    }
                }
                is ConnectOutcome.Fatal -> {
                    _events.emit(IncomingEvent.ConnectionLost("fatal: ${connectResult.reason}"))
                    _connectionState.value = ConnectionState.Disconnected
                    return
                }
                is ConnectOutcome.Retryable -> {
                    attempt++
                    if (attempt > config.maxRetries) {
                        _events.emit(
                            IncomingEvent.ConnectionLost("max retries ($attempt) exhausted"),
                        )
                        _connectionState.value = ConnectionState.Disconnected
                        return
                    }
                    _events.emit(IncomingEvent.ConnectionLost("connect failed"))
                    continue
                }
            }
        }
    }

    private suspend fun tryConnect(): ConnectOutcome {
        val lastSeq = lastSeenSeqState.value
        val options =
            ConnectOptions(
                lastEventSeq = if (lastSeq > 0L) lastSeq else null,
            )
        return try {
            val result = delegate.connect(endpoint, identity, options)
            result.fold(
                onSuccess = { session -> ConnectOutcome.Success(session) },
                onFailure = { t ->
                    if (t is RelayError.Rejected && t.reason in FATAL_REASONS) {
                        ConnectOutcome.Fatal(t.reason)
                    } else {
                        ConnectOutcome.Retryable
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ConnectOutcome.Retryable
        }
    }

    /**
     * Consume the session's incoming flow until it completes or a terminal
     * message arrives. Returns the reason the session ended.
     */
    private suspend fun handleSession(
        session: TransportSession,
        hasGap: Boolean,
    ): SessionOutcome {
        try {
            session.incoming.collect { frame ->
                when (val msg = frame.message) {
                    is WireMessage.HelloErr -> {
                        if (msg.reason in FATAL_REASONS) {
                            _events.emit(IncomingEvent.ConnectionLost("fatal: ${msg.reason}"))
                            session.close()
                            throw FatalRejectedException(msg.reason)
                        }
                    }
                    is WireMessage.ReplayEnd -> {
                        _events.emit(IncomingEvent.ReplayComplete(msg.replayedCount, hasGap))
                        _connectionState.value =
                            ConnectionState.Connected(session.helloOk.serverTimeMs)
                        _events.emit(IncomingEvent.ConnectionEstablished)
                    }
                    is WireMessage.SessionEnded -> {
                        _events.emit(IncomingEvent.ConnectionLost("session_ended: ${msg.reason}"))
                        session.close()
                        throw SessionEndedException()
                    }
                    else -> {
                        // Application message — update seq tracker and emit
                        val seq = frame.seq
                        if (seq != null) {
                            updateLastSeenSeq(seq)
                            _events.emit(IncomingEvent.Message(seq, msg))
                        } else {
                            // Unsequenced non-control message — still emit
                            _events.emit(IncomingEvent.Message(0L, msg))
                        }
                    }
                }
            }
        } catch (_: FatalRejectedException) {
            return SessionOutcome.FATAL
        } catch (_: SessionEndedException) {
            return SessionOutcome.SESSION_ENDED
        } catch (e: CancellationException) {
            throw e // propagate structured cancellation
        } catch (_: Exception) {
            // Flow completed or network error — will reconnect
        }
        // Flow completed normally = connection lost
        return SessionOutcome.DISCONNECTED
    }

    private fun updateLastSeenSeq(seq: Long) {
        lastSeenSeqState.update { current -> maxOf(current, seq) }
    }

    /**
     * Compute the next retry delay with exponential backoff and jitter.
     * The result is always clamped to `[initialDelayMs, maxDelayMs]`.
     */
    internal fun nextDelay(attempt: Int): Long {
        val base =
            min(
                config.initialDelayMs * config.multiplier.pow(attempt.toDouble()),
                config.maxDelayMs.toDouble(),
            ).toLong()
        val jitter = (base * config.jitterFraction * (random.nextDouble() * 2.0 - 1.0)).toLong()
        return (base + jitter).coerceIn(config.initialDelayMs, config.maxDelayMs)
    }

    private enum class SessionOutcome { FATAL, SESSION_ENDED, DISCONNECTED }

    private sealed class ConnectOutcome {
        data class Success(
            val session: TransportSession,
        ) : ConnectOutcome()

        data class Fatal(
            val reason: String,
        ) : ConnectOutcome()

        data object Retryable : ConnectOutcome()
    }

    /** Sentinel thrown inside [handleSession] to break the collect loop. */
    private class FatalRejectedException(
        reason: String,
    ) : Exception(reason)

    private class SessionEndedException : Exception()

    private companion object {
        const val EVENT_BUFFER: Int = 256
        val FATAL_REASONS: Set<String> = setOf("revoked", "unpaired", "sig")
    }
}
