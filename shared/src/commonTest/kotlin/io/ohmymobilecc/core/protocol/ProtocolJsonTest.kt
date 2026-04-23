package io.ohmymobilecc.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for the shared [ProtocolJson] configuration.
 *
 * These flags are load-bearing: parsers across relay and mobile clients MUST
 * agree or we get silent divergence on new CC fields.
 */
class ProtocolJsonTest {
    @Test
    fun `default instance is lenient-free and ignores unknown keys`() {
        val cfg = ProtocolJson.default.configuration
        assertTrue(cfg.ignoreUnknownKeys, "ignoreUnknownKeys must be true for forward-compat")
        assertFalse(cfg.isLenient, "isLenient must be false to reject malformed JSON loudly")
    }

    @Test
    fun `default instance omits default-valued fields on encode`() {
        val cfg = ProtocolJson.default.configuration
        assertFalse(cfg.encodeDefaults, "encodeDefaults must be false to keep wire payloads minimal")
    }

    @Test
    fun `class discriminator is explicit type for CC compatibility`() {
        val cfg = ProtocolJson.default.configuration
        assertEquals("type", cfg.classDiscriminator)
    }
}
