package io.ohmymobilecc.relay.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Token-based per-sessionId slot registry enforcing the
 * `openspec/specs/protocol/spec.md` single-active-WS-per-session rule.
 *
 * A connection acquires ownership via [claim] supplying a caller-unique
 * `token` (typically a monotonic counter or `System.identityHashCode` of
 * the ws session). [release] is a no-op unless the supplied token matches
 * the registered owner — this prevents a stale connection from evicting
 * the replacement after it has already disconnected.
 */
public class SingleConnectionRegistry {
    private val slots = ConcurrentHashMap<String, Long>()

    /**
     * Try to become the owner of [sessionId] with [token]. Returns true if
     * the slot was free (and is now held by [token]), false if another
     * token already owns it.
     */
    public fun claim(
        sessionId: String,
        token: Long,
    ): Boolean = slots.putIfAbsent(sessionId, token) == null

    /**
     * Release the slot iff the current owner is [token]. Wrong token or
     * unknown session is silently ignored.
     */
    public fun release(
        sessionId: String,
        token: Long,
    ) {
        slots.remove(sessionId, token)
    }

    /** Debug / test helper — the token currently holding [sessionId], or null. */
    public fun currentToken(sessionId: String): Long? = slots[sessionId]
}
