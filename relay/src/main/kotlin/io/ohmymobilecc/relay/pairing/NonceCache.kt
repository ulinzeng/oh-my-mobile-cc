package io.ohmymobilecc.relay.pairing

import java.util.LinkedHashMap

/**
 * Bounded LRU + TTL cache for accepted `ClientHello` nonces per
 * `openspec/specs/pairing/spec.md` §Replay 防护.
 *
 * - TTL: entries older than [ttlMs] (default 10 min) are evictable, and on
 *   the next call an incoming same-nonce offer is accepted because the
 *   stale row is purged first.
 * - Size bound: at most [maxEntries] rows retained; LRU eviction via
 *   [LinkedHashMap] access-order.
 * - Thread-safety: whole-map `@Synchronized` — contention is low because
 *   this is hit at most once per WS hello, not per frame.
 */
@Suppress("MagicNumber") // TTL literals & LinkedHashMap default sizing
public class NonceCache(
    private val maxEntries: Int = 10_000,
    private val ttlMs: Long = 10L * 60L * 1000L,
) {
    // accessOrder = true so the LRU discipline picks up on read-but-keep paths.
    private val map: LinkedHashMap<String, Long> =
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Long>): Boolean = size > maxEntries
        }

    /**
     * Try to record [nonce] as seen at [nowMs]. Returns true if accepted
     * (first time, or re-accepted after TTL expiry), false on replay.
     */
    @Synchronized
    public fun offer(
        nonce: String,
        nowMs: Long,
    ): Boolean {
        purgeExpired(nowMs)
        val existing = map[nonce]
        if (existing != null && (nowMs - existing) < ttlMs) return false
        map[nonce] = nowMs
        return true
    }

    private fun purgeExpired(nowMs: Long) {
        // LinkedHashMap access-order means the *least recently accessed* is
        // at the head — that is close to, but not exactly, oldest by
        // `nowMs`. For a pure time-TTL we iterate and remove every stale
        // entry; eviction cost is bounded by ttl + traffic rate.
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if ((nowMs - e.value) >= ttlMs) it.remove()
        }
    }
}
