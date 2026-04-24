package io.ohmymobilecc.relay.server

import io.ohmymobilecc.core.protocol.WireMessage
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory ring buffer that stores [SequencedEvent]s for replay.
 *
 * Every appended [WireMessage] is assigned a monotonically increasing
 * sequence number (starting at 1). When the buffer is full the oldest
 * event is silently evicted.
 *
 * Thread safety is provided by a [ReentrantReadWriteLock]: [append]
 * acquires the write lock while [replaySince], [oldestSeq], [latestSeq]
 * and [size] acquire the read lock.
 *
 * @param capacity maximum number of events retained (must be > 0).
 */
public class EventLog(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val seq = AtomicLong(0L)
    private val lock = ReentrantReadWriteLock()

    // Ring buffer backed by a plain array; head is the index of the oldest
    // element, count tracks how many slots are occupied.
    private val ring = arrayOfNulls<SequencedEvent>(capacity)
    private var head = 0
    private var count = 0

    /**
     * Append a [WireMessage] to the log, returning the newly created
     * [SequencedEvent] with its assigned sequence number.
     */
    public fun append(
        msg: WireMessage,
        nowMs: Long,
    ): SequencedEvent =
        lock.write {
            val event =
                SequencedEvent(
                    seq = seq.incrementAndGet(),
                    timestampMs = nowMs,
                    message = msg,
                )
            val index = (head + count) % capacity
            ring[index] = event
            if (count == capacity) {
                // Buffer full — advance head to evict the oldest entry.
                head = (head + 1) % capacity
            } else {
                count++
            }
            event
        }

    /**
     * Return every event whose `seq` is strictly greater than [afterSeq].
     *
     * * If [afterSeq] is less than the oldest retained seq the entire
     *   buffer is returned (best-effort replay).
     * * If [afterSeq] >= [latestSeq] an empty list is returned.
     */
    public fun replaySince(afterSeq: Long): List<SequencedEvent> =
        lock.read {
            if (count == 0) return@read emptyList()

            val oldest = ring[head]!!.seq
            val latest = ring[(head + count - 1) % capacity]!!.seq

            if (afterSeq >= latest) return@read emptyList()

            // Determine the start offset inside the ring.
            val startSeq = if (afterSeq < oldest) oldest else afterSeq + 1
            val skip = (startSeq - oldest).toInt()

            List(count - skip) { i ->
                ring[(head + skip + i) % capacity]!!
            }
        }

    /** Sequence number of the oldest retained event, or `null` if empty. */
    public fun oldestSeq(): Long? =
        lock.read {
            if (count == 0) null else ring[head]!!.seq
        }

    /** Sequence number of the newest event, or `0` if no events have been appended. */
    public fun latestSeq(): Long =
        lock.read {
            if (count == 0) 0L else ring[(head + count - 1) % capacity]!!.seq
        }

    /** Number of events currently retained in the buffer. */
    public fun size(): Int = lock.read { count }

    private companion object {
        private const val DEFAULT_CAPACITY = 10_000
    }
}
