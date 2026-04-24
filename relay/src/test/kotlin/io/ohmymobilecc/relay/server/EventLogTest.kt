package io.ohmymobilecc.relay.server

import io.ohmymobilecc.core.protocol.WireMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventLogTest {
    private fun msg(id: Int): WireMessage.ChatMessage = WireMessage.ChatMessage(sessionId = "s1", text = "msg-$id")

    // ── Normal append & replay ──────────────────────────────────────

    @Test
    fun `append assigns monotonically increasing seq starting at 1`() {
        val log = EventLog(capacity = 100)
        val e1 = log.append(msg(1), nowMs = 1000L)
        val e2 = log.append(msg(2), nowMs = 2000L)
        val e3 = log.append(msg(3), nowMs = 3000L)

        assertEquals(1L, e1.seq)
        assertEquals(2L, e2.seq)
        assertEquals(3L, e3.seq)
        assertEquals(1000L, e1.timestampMs)
        assertEquals(msg(1), e1.message)
    }

    @Test
    fun `replaySince returns events with seq greater than afterSeq`() {
        val log = EventLog(capacity = 200)
        repeat(100) { i -> log.append(msg(i), nowMs = i.toLong()) }

        val replayed = log.replaySince(afterSeq = 42)
        assertEquals(58, replayed.size)
        assertEquals(43L, replayed.first().seq)
        assertEquals(100L, replayed.last().seq)
    }

    // ── Ring eviction ───────────────────────────────────────────────

    @Test
    fun `ring buffer evicts oldest when capacity exceeded`() {
        val cap = 100
        val log = EventLog(capacity = cap)
        repeat(150) { i -> log.append(msg(i), nowMs = i.toLong()) }

        assertEquals(cap, log.size())
        assertEquals(51L, log.oldestSeq())
        assertEquals(150L, log.latestSeq())
    }

    // ── afterSeq before buffer range (best-effort replay) ───────────

    @Test
    fun `replaySince with afterSeq before oldest returns entire buffer`() {
        val log = EventLog(capacity = 50)
        repeat(100) { i -> log.append(msg(i), nowMs = i.toLong()) }

        // oldest seq is 51, asking for afterSeq=10 which is before oldest
        val replayed = log.replaySince(afterSeq = 10)
        assertEquals(50, replayed.size)
        assertEquals(51L, replayed.first().seq)
        assertEquals(100L, replayed.last().seq)
    }

    // ── afterSeq >= latestSeq (nothing to replay) ───────────────────

    @Test
    fun `replaySince with afterSeq equal to latestSeq returns empty`() {
        val log = EventLog(capacity = 100)
        repeat(5) { i -> log.append(msg(i), nowMs = i.toLong()) }

        assertTrue(log.replaySince(afterSeq = 5).isEmpty())
    }

    @Test
    fun `replaySince with afterSeq greater than latestSeq returns empty`() {
        val log = EventLog(capacity = 100)
        repeat(5) { i -> log.append(msg(i), nowMs = i.toLong()) }

        assertTrue(log.replaySince(afterSeq = 999).isEmpty())
    }

    // ── Empty EventLog ──────────────────────────────────────────────

    @Test
    fun `empty log has null oldestSeq and zero latestSeq`() {
        val log = EventLog(capacity = 100)
        assertNull(log.oldestSeq())
        assertEquals(0L, log.latestSeq())
        assertEquals(0, log.size())
    }

    @Test
    fun `replaySince on empty log returns empty list`() {
        val log = EventLog(capacity = 100)
        assertTrue(log.replaySince(afterSeq = 0).isEmpty())
        assertTrue(log.replaySince(afterSeq = 5).isEmpty())
    }

    // ── afterSeq = 0 replays everything ─────────────────────────────

    @Test
    fun `replaySince afterSeq 0 returns all events`() {
        val log = EventLog(capacity = 100)
        repeat(10) { i -> log.append(msg(i), nowMs = i.toLong()) }

        val replayed = log.replaySince(afterSeq = 0)
        assertEquals(10, replayed.size)
        assertEquals(1L, replayed.first().seq)
    }

    // ── Concurrent read-write safety ────────────────────────────────

    @Test
    fun `concurrent append and replaySince do not throw or lose data`() {
        val log = EventLog(capacity = 5_000)
        val writers = 4
        val eventsPerWriter = 500
        val totalEvents = writers * eventsPerWriter
        val pool = Executors.newFixedThreadPool(writers + 2)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(writers)

        // Writers
        repeat(writers) {
            pool.submit {
                startLatch.await()
                repeat(eventsPerWriter) { i ->
                    log.append(msg(i), nowMs = System.currentTimeMillis())
                }
                doneLatch.countDown()
            }
        }

        // Readers (running concurrently with writers)
        val readerErrors = mutableListOf<Throwable>()
        repeat(2) {
            pool.submit {
                startLatch.await()
                try {
                    repeat(200) {
                        val events = log.replaySince(afterSeq = 0)
                        // Each event's seq must be unique and positive
                        val seqs = events.map { e -> e.seq }
                        assertEquals(seqs.size, seqs.toSet().size, "Duplicate seqs detected")
                        assertTrue(seqs.all { s -> s > 0 }, "Non-positive seq detected")
                    }
                } catch (e: Throwable) {
                    synchronized(readerErrors) { readerErrors.add(e) }
                }
            }
        }

        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertTrue(readerErrors.isEmpty(), "Reader errors: $readerErrors")
        assertEquals(totalEvents, log.size())
        assertEquals(totalEvents.toLong(), log.latestSeq())
    }

    // ── size tracks correctly through eviction ──────────────────────

    @Test
    fun `size never exceeds capacity`() {
        val cap = 10
        val log = EventLog(capacity = cap)
        repeat(25) { i ->
            log.append(msg(i), nowMs = i.toLong())
            assertTrue(log.size() <= cap)
        }
        assertEquals(cap, log.size())
    }
}
