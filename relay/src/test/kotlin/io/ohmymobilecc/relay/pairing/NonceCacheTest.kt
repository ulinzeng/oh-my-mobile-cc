package io.ohmymobilecc.relay.pairing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonceCacheTest {
    @Test
    fun accepts_new_nonce() {
        val cache = NonceCache(maxEntries = 10)
        assertTrue(cache.offer(nonce = "n1", nowMs = 1_000))
    }

    @Test
    fun rejects_duplicate_within_ttl() {
        val cache = NonceCache(maxEntries = 10)
        assertTrue(cache.offer("n1", nowMs = 1_000))
        assertFalse(cache.offer("n1", nowMs = 1_000 + 500))
    }

    @Test
    fun accepts_same_nonce_after_ttl_expiry() {
        val cache = NonceCache(maxEntries = 10)
        assertTrue(cache.offer("n1", nowMs = 1_000))
        // Spec: 10-minute window. At exactly 10 min (inclusive) the entry is
        // treated as expired and a fresh offer with same nonce is accepted.
        assertTrue(cache.offer("n1", nowMs = 1_000 + 10 * 60 * 1000))
    }

    @Test
    fun bounded_size_evicts_oldest_via_lru() {
        // With cap=3 and insertion-order LRU, inserting a 4th entry
        // evicts the least-recently-accessed one and makes it re-offerable.
        // The survivors stay rejected while still within TTL.
        val cache = NonceCache(maxEntries = 3)
        assertTrue(cache.offer("n1", nowMs = 1))
        assertTrue(cache.offer("n2", nowMs = 2))
        assertTrue(cache.offer("n3", nowMs = 3))
        assertTrue(cache.offer("n4", nowMs = 4)) // evicts n1 (eldest)
        // n1 was kicked out, so it's acceptable again.
        assertTrue(cache.offer("n1", nowMs = 5))
        // n4 was the last to be inserted and survived the previous eviction —
        // still within TTL, still rejected.
        assertFalse(cache.offer("n4", nowMs = 6))
    }

    @Test
    fun distinct_nonces_all_pass() {
        val cache = NonceCache(maxEntries = 100)
        repeat(50) { i ->
            assertTrue(cache.offer("nonce-$i", nowMs = i.toLong()))
        }
    }
}
