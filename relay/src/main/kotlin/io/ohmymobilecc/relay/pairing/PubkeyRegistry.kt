package io.ohmymobilecc.relay.pairing

import io.ohmymobilecc.core.pairing.DeviceId
import java.util.concurrent.ConcurrentHashMap

/**
 * A single paired device's record. Immutable per-version — on revoke we
 * replace the row with a [revokedAtMs]-populated copy rather than mutating
 * in place.
 */
public data class RegisteredDevice(
    val deviceId: DeviceId,
    val publicKey: ByteArray,
    val registeredAtMs: Long,
    val revokedAtMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegisteredDevice) return false
        return deviceId == other.deviceId &&
            publicKey.contentEquals(other.publicKey) &&
            registeredAtMs == other.registeredAtMs &&
            revokedAtMs == other.revokedAtMs
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + registeredAtMs.hashCode()
        result = 31 * result + (revokedAtMs?.hashCode() ?: 0)
        return result
    }
}

public interface PubkeyRegistry {
    public fun register(
        deviceId: DeviceId,
        publicKey: ByteArray,
        atMs: Long,
    )

    public fun find(deviceId: DeviceId): RegisteredDevice?

    public fun revoke(
        deviceId: DeviceId,
        atMs: Long,
    )
}

/**
 * In-memory adapter for [PubkeyRegistry], used until SqlDelight persistence
 * lands in W2.3.
 */
public class InMemoryPubkeyRegistry : PubkeyRegistry {
    private val map = ConcurrentHashMap<DeviceId, RegisteredDevice>()

    override fun register(
        deviceId: DeviceId,
        publicKey: ByteArray,
        atMs: Long,
    ) {
        map[deviceId] = RegisteredDevice(deviceId, publicKey, atMs)
    }

    override fun find(deviceId: DeviceId): RegisteredDevice? = map[deviceId]

    override fun revoke(
        deviceId: DeviceId,
        atMs: Long,
    ) {
        map.computeIfPresent(deviceId) { _, v -> v.copy(revokedAtMs = atMs) }
    }

    /** `true` iff at least one device is currently registered. Used by the `pair` CLI poll loop. */
    public fun anyRegistered(): Boolean = map.isNotEmpty()
}
