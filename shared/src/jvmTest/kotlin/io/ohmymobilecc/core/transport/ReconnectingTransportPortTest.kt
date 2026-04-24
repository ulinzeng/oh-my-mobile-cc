package io.ohmymobilecc.core.transport

import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [ReconnectingTransportPort]. Uses a [FakeTransportPort]
 * that scripts connect outcomes (success / failure / rejection) so tests
 * control the full lifecycle without real networking.
 *
 * Tests synchronize via [Flow.first] / [Flow.take] on observable state
 * rather than `advanceUntilIdle()` so the background connect-loop runs
 * naturally through the test dispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReconnectingTransportPortTest {
    // ---------------------------------------------------------------
    // 1. Happy path: first connect succeeds, messages flow through
    // ---------------------------------------------------------------

    @Test
    fun `first connect succeeds and streams messages`() =
        runTest {
            val fake = FakeTransportPort()
            val session = FakeTransportSession()
            fake.enqueueSuccess(session)

            val port = newPort(fake)
            port.start(backgroundScope)

            val established = port.events.first()
            assertIs<IncomingEvent.ConnectionEstablished>(established)
            assertIs<ConnectionState.Connected>(port.connectionState.value)

            session.pushFrame(ReceivedFrame(seq = 1L, message = sampleApproval("a1")))
            val msg = port.events.first()
            assertIs<IncomingEvent.Message>(msg)
            assertEquals(1L, msg.seq)
            assertEquals(1L, port.lastSeenSeq())

            port.close()
        }

    // ---------------------------------------------------------------
    // 2. Auto-reconnect after disconnect
    // ---------------------------------------------------------------

    @Test
    fun `reconnects after connection lost`() =
        runTest {
            val fake = FakeTransportPort()
            val session1 = FakeTransportSession()
            val session2 = FakeTransportSession()
            fake.enqueueSuccess(session1)
            fake.enqueueSuccess(session2)

            val port = newPort(fake, config = ReconnectConfig(initialDelayMs = 100))
            port.start(backgroundScope)

            // Wait for first connection
            port.events.first { it is IncomingEvent.ConnectionEstablished }

            // Simulate disconnect
            session1.complete()

            // Wait for reconnection: ConnectionLost then ConnectionEstablished again
            val lost = port.events.first { it is IncomingEvent.ConnectionLost }
            assertIs<IncomingEvent.ConnectionLost>(lost)

            val reconnected = port.events.first { it is IncomingEvent.ConnectionEstablished }
            assertIs<IncomingEvent.ConnectionEstablished>(reconnected)
            assertEquals(2, fake.connectCount)

            port.close()
        }

    // ---------------------------------------------------------------
    // 3. Exponential backoff delay calculation
    // ---------------------------------------------------------------

    @Test
    fun `nextDelay respects exponential backoff with cap`() {
        val fake = FakeTransportPort()
        val config =
            ReconnectConfig(
                initialDelayMs = 1_000,
                maxDelayMs = 10_000,
                multiplier = 2.0,
                jitterFraction = 0.0,
            )
        val port = newPort(fake, config = config)

        assertEquals(1_000L, port.nextDelay(0))
        assertEquals(2_000L, port.nextDelay(1))
        assertEquals(4_000L, port.nextDelay(2))
        assertEquals(8_000L, port.nextDelay(3))
        assertEquals(10_000L, port.nextDelay(4))
        assertEquals(10_000L, port.nextDelay(10))
    }

    @Test
    fun `nextDelay applies jitter within expected range`() {
        val fake = FakeTransportPort()
        val config =
            ReconnectConfig(
                initialDelayMs = 1_000,
                maxDelayMs = 30_000,
                multiplier = 2.0,
                jitterFraction = 0.2,
            )
        val port = newPort(fake, config = config, random = Random(42))

        // attempt=1 → base=2000, jitter range ±400 → [1600, 2400], clamped to [1000, 30000]
        val d = port.nextDelay(1)
        assertTrue(d in 1_000L..2_400L, "delay=$d should be in [1000, 2400]")
    }

    // ---------------------------------------------------------------
    // 4. FATAL reason stops reconnection
    // ---------------------------------------------------------------

    @Test
    fun `fatal rejection revoked stops reconnection`() =
        runTest {
            val fake = FakeTransportPort()
            fake.enqueueFailure(RelayError.Rejected("revoked"))

            val port = newPort(fake)
            port.start(backgroundScope)

            val lost = port.events.first()
            assertIs<IncomingEvent.ConnectionLost>(lost)
            assertTrue(lost.reason.contains("revoked"))

            // Wait for state to settle
            port.connectionState.first { it is ConnectionState.Disconnected }
            assertEquals(1, fake.connectCount)

            port.close()
        }

    @Test
    fun `fatal rejection unpaired stops reconnection`() =
        runTest {
            val fake = FakeTransportPort()
            fake.enqueueFailure(RelayError.Rejected("unpaired"))

            val port = newPort(fake)
            port.start(backgroundScope)

            val lost = port.events.first()
            assertIs<IncomingEvent.ConnectionLost>(lost)
            assertTrue(lost.reason.contains("unpaired"))
            assertEquals(1, fake.connectCount)

            port.close()
        }

    @Test
    fun `fatal rejection sig stops reconnection`() =
        runTest {
            val fake = FakeTransportPort()
            fake.enqueueFailure(RelayError.Rejected("sig"))

            val port = newPort(fake)
            port.start(backgroundScope)

            val lost = port.events.first()
            assertIs<IncomingEvent.ConnectionLost>(lost)
            assertTrue(lost.reason.contains("sig"))
            assertEquals(1, fake.connectCount)

            port.close()
        }

    @Test
    fun `non-fatal rejection retries`() =
        runTest {
            val fake = FakeTransportPort()
            fake.enqueueFailure(RelayError.Rejected("skew"))
            val session = FakeTransportSession()
            fake.enqueueSuccess(session)

            val port = newPort(fake, config = ReconnectConfig(initialDelayMs = 100))
            port.start(backgroundScope)

            val established = port.events.first { it is IncomingEvent.ConnectionEstablished }
            assertIs<IncomingEvent.ConnectionEstablished>(established)
            assertEquals(2, fake.connectCount)

            port.close()
        }

    // ---------------------------------------------------------------
    // 5. Seq tracking
    // ---------------------------------------------------------------

    @Test
    fun `lastSeenSeq tracks highest seq monotonically`() =
        runTest {
            val fake = FakeTransportPort()
            val session = FakeTransportSession()
            fake.enqueueSuccess(session)

            val port = newPort(fake)
            port.start(backgroundScope)
            port.events.first { it is IncomingEvent.ConnectionEstablished }

            session.pushFrame(ReceivedFrame(seq = 5L, message = sampleApproval("a1")))
            port.events.first { it is IncomingEvent.Message }
            assertEquals(5L, port.lastSeenSeq())

            session.pushFrame(ReceivedFrame(seq = 3L, message = sampleApproval("a2")))
            port.events.first { it is IncomingEvent.Message }
            assertEquals(5L, port.lastSeenSeq()) // should NOT decrease

            session.pushFrame(ReceivedFrame(seq = 10L, message = sampleApproval("a3")))
            port.events.first { it is IncomingEvent.Message }
            assertEquals(10L, port.lastSeenSeq())

            port.close()
        }

    @Test
    fun `initialLastSeq is used for first reconnection`() =
        runTest {
            val fake = FakeTransportPort()
            val session = FakeTransportSession()
            fake.enqueueSuccess(session)

            val port = newPort(fake, initialLastSeq = 42L)
            assertEquals(42L, port.lastSeenSeq())

            port.start(backgroundScope)

            // Wait for connection to establish — that means connect was called
            port.events.first { it is IncomingEvent.ConnectionEstablished }

            assertEquals(42L, fake.lastConnectOptions?.lastEventSeq)

            port.close()
        }

    // ---------------------------------------------------------------
    // 6. State machine transitions
    // ---------------------------------------------------------------

    @Test
    fun `state transitions Disconnected to Connecting to Connected`() =
        runTest {
            val fake = FakeTransportPort()
            val session = FakeTransportSession()
            fake.enqueueSuccess(session)

            val port = newPort(fake)

            assertIs<ConnectionState.Disconnected>(port.connectionState.value)

            // Collect states into a list using a coroutine started BEFORE port.start
            val states = mutableListOf<ConnectionState>()
            val collectJob =
                launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                    port.connectionState.collect { states.add(it) }
                }

            port.start(backgroundScope)

            // Wait for Connected
            port.connectionState.first { it is ConnectionState.Connected }

            assertTrue(states.any { it is ConnectionState.Disconnected }, "expected Disconnected in $states")
            assertTrue(states.any { it is ConnectionState.Connecting }, "expected Connecting in $states")
            assertTrue(states.any { it is ConnectionState.Connected }, "expected Connected in $states")

            collectJob.cancel()
            port.close()
        }

    @Test
    fun `state transitions through Reconnecting on retry`() =
        runTest {
            val fake = FakeTransportPort()
            fake.enqueueFailure(RelayError.Network(RuntimeException("net")))
            val session = FakeTransportSession()
            fake.enqueueSuccess(session)

            val port = newPort(fake, config = ReconnectConfig(initialDelayMs = 100))

            val states = mutableListOf<ConnectionState>()
            val collectJob =
                launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                    port.connectionState.collect { states.add(it) }
                }

            port.start(backgroundScope)

            // Wait for eventual Connected state after retry
            port.connectionState.first { it is ConnectionState.Connected }

            assertTrue(states.any { it is ConnectionState.Reconnecting }, "expected Reconnecting in $states")
            assertTrue(states.any { it is ConnectionState.Connected }, "expected Connected in $states")

            collectJob.cancel()
            port.close()
        }

    // ---------------------------------------------------------------
    // 7. ReplayEnd handling
    // ---------------------------------------------------------------

    @Test
    fun `ReplayEnd emits ReplayComplete and transitions to Connected`() =
        runTest {
            val fake = FakeTransportPort()
            val session =
                FakeTransportSession(
                    helloOk =
                        WireMessage.HelloOk(
                            serverTimeMs = 1_000L,
                            protocolVersion = 1,
                            oldestSeq = 1L,
                            latestSeq = 5L,
                        ),
                )
            fake.enqueueSuccess(session)

            val port = newPort(fake, initialLastSeq = 2L)
            port.start(backgroundScope)

            // Should enter Replaying state
            port.connectionState.first { it is ConnectionState.Replaying }

            // Push replay messages then ReplayEnd
            session.pushFrame(ReceivedFrame(seq = 3L, message = sampleApproval("r1")))
            session.pushFrame(ReceivedFrame(seq = 4L, message = sampleApproval("r2")))
            session.pushFrame(ReceivedFrame(seq = 5L, message = sampleApproval("r3")))
            session.pushFrame(
                ReceivedFrame(
                    seq = null,
                    message = WireMessage.ReplayEnd(replayedCount = 3, fromSeq = 3L, toSeq = 5L),
                ),
            )

            // Collect: 3 Messages + ReplayComplete + ConnectionEstablished
            val events = port.events.take(5).toList()

            val replayComplete = events.filterIsInstance<IncomingEvent.ReplayComplete>().first()
            assertEquals(3, replayComplete.count)
            assertEquals(false, replayComplete.hasGap) // oldestSeq=1 <= lastSeq+1=3

            assertTrue(events.any { it is IncomingEvent.ConnectionEstablished })
            assertIs<ConnectionState.Connected>(port.connectionState.value)

            port.close()
        }

    @Test
    fun `ReplayEnd detects gap when oldestSeq greater than lastSeenSeq plus one`() =
        runTest {
            val fake = FakeTransportPort()
            val session =
                FakeTransportSession(
                    helloOk =
                        WireMessage.HelloOk(
                            serverTimeMs = 1_000L,
                            protocolVersion = 1,
                            oldestSeq = 10L,
                            latestSeq = 15L,
                        ),
                )
            fake.enqueueSuccess(session)

            val port = newPort(fake, initialLastSeq = 2L)
            port.start(backgroundScope)

            port.connectionState.first { it is ConnectionState.Replaying }

            session.pushFrame(
                ReceivedFrame(
                    seq = null,
                    message = WireMessage.ReplayEnd(replayedCount = 5, fromSeq = 10L, toSeq = 15L),
                ),
            )

            val replayComplete = port.events.first { it is IncomingEvent.ReplayComplete }
            assertIs<IncomingEvent.ReplayComplete>(replayComplete)
            assertTrue(replayComplete.hasGap)

            port.close()
        }

    // ---------------------------------------------------------------
    // 8. SessionEnded stops reconnection
    // ---------------------------------------------------------------

    @Test
    fun `SessionEnded stops reconnection loop`() =
        runTest {
            val fake = FakeTransportPort()
            val session = FakeTransportSession()
            fake.enqueueSuccess(session)

            val port = newPort(fake, config = ReconnectConfig(initialDelayMs = 100))
            port.start(backgroundScope)
            port.events.first { it is IncomingEvent.ConnectionEstablished }

            session.pushFrame(
                ReceivedFrame(
                    seq = null,
                    message = WireMessage.SessionEnded(sessionId = "S1", reason = "host_closed"),
                ),
            )

            val lost = port.events.first { it is IncomingEvent.ConnectionLost }
            assertIs<IncomingEvent.ConnectionLost>(lost)
            assertTrue(lost.reason.contains("session_ended"))

            port.connectionState.first { it is ConnectionState.Disconnected }
            assertEquals(1, fake.connectCount)

            port.close()
        }

    // ---------------------------------------------------------------
    // 9. maxRetries exhaustion
    // ---------------------------------------------------------------

    @Test
    fun `maxRetries exhaustion stops loop`() =
        runTest {
            val fake = FakeTransportPort()
            // 1 initial + 3 retries = 4 attempts total
            repeat(4) { fake.enqueueFailure(RelayError.Network(RuntimeException("net"))) }

            val port =
                newPort(
                    fake,
                    config = ReconnectConfig(initialDelayMs = 100, maxRetries = 3),
                )
            port.start(backgroundScope)

            // Wait for the terminal ConnectionLost about exhaustion
            val lost =
                port.events.first {
                    it is IncomingEvent.ConnectionLost &&
                        (it as IncomingEvent.ConnectionLost).reason.contains("max retries")
                }
            assertIs<IncomingEvent.ConnectionLost>(lost)
            port.connectionState.first { it is ConnectionState.Disconnected }
            assertEquals(4, fake.connectCount)

            port.close()
        }

    // ---------------------------------------------------------------
    // 10. close() transitions to Disconnected
    // ---------------------------------------------------------------

    @Test
    fun `close transitions to Disconnected`() =
        runTest {
            val fake = FakeTransportPort()
            val session = FakeTransportSession()
            fake.enqueueSuccess(session)

            val port = newPort(fake)
            port.start(backgroundScope)
            port.events.first { it is IncomingEvent.ConnectionEstablished }

            assertIs<ConnectionState.Connected>(port.connectionState.value)

            port.close()

            assertIs<ConnectionState.Disconnected>(port.connectionState.value)
        }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun newPort(
        fake: FakeTransportPort,
        config: ReconnectConfig = ReconnectConfig(),
        initialLastSeq: Long? = null,
        random: Random = Random(0),
    ): ReconnectingTransportPort =
        ReconnectingTransportPort(
            delegate = fake,
            endpoint = TransportEndpoint(host = "127.0.0.1", port = 9999, sessionId = "S1"),
            identity = dummyIdentity(),
            config = config,
            random = random,
            initialLastSeq = initialLastSeq,
        )

    private fun dummyIdentity(): DeviceIdentity {
        val pk = ByteArray(32) { 0x11 }
        val sk = ByteArray(64) { 0x22 }
        return DeviceIdentity(
            deviceId =
                io.ohmymobilecc.core.pairing
                    .DeviceId("test-device-id"),
            publicKey = pk,
            secretKey = sk,
        )
    }

    private fun sampleApproval(id: String): WireMessage.ApprovalRequested =
        WireMessage.ApprovalRequested(
            approvalId = id,
            sessionId = "S1",
            tool = "Bash",
            input = JsonObject(emptyMap()),
            proposedAt = 0L,
        )
}

// ===================================================================
// Fakes
// ===================================================================

/**
 * Scriptable [TransportPort] for unit testing [ReconnectingTransportPort].
 * Each call to [connect] dequeues the next scripted outcome.
 */
private class FakeTransportPort : TransportPort {
    private val outcomes = ArrayDeque<Result<TransportSession>>()
    var connectCount: Int = 0
        private set
    var lastConnectOptions: ConnectOptions? = null
        private set

    fun enqueueSuccess(session: FakeTransportSession) {
        outcomes.addLast(Result.success(session))
    }

    fun enqueueFailure(error: Throwable) {
        outcomes.addLast(Result.failure(error))
    }

    override suspend fun connect(
        endpoint: TransportEndpoint,
        identity: DeviceIdentity,
        options: ConnectOptions,
    ): Result<TransportSession> {
        connectCount++
        lastConnectOptions = options
        return if (outcomes.isNotEmpty()) {
            outcomes.removeFirst()
        } else {
            // Block forever if no more outcomes scripted
            CompletableDeferred<Result<TransportSession>>().await()
        }
    }

    override suspend fun shutdown() { /* no-op */ }
}

/**
 * Scriptable [TransportSession] backed by a [Channel] so tests can push
 * frames on demand and signal connection close via [complete].
 */
private class FakeTransportSession(
    override val helloOk: WireMessage.HelloOk =
        WireMessage.HelloOk(
            serverTimeMs = 1_000_000L,
            protocolVersion = 1,
            oldestSeq = 0L,
            latestSeq = 0L,
        ),
) : TransportSession {
    private val channel = Channel<ReceivedFrame>(capacity = 64)
    override val incoming: Flow<ReceivedFrame> = channel.consumeAsFlow()

    private val sent = mutableListOf<WireMessage>()

    suspend fun pushFrame(frame: ReceivedFrame) {
        channel.send(frame)
    }

    /** Close the channel to simulate a connection drop. */
    fun complete() {
        channel.close()
    }

    override suspend fun send(message: WireMessage) {
        sent.add(message)
    }

    override suspend fun close() {
        channel.close()
    }
}
