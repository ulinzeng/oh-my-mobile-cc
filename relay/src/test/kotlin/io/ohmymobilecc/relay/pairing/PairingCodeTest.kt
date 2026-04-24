package io.ohmymobilecc.relay.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingCodeTest {
    @Test
    fun valid_within_5_minutes() {
        val code = PairingCode(digits = "482193", issuedAtMs = 1_000_000L)
        assertTrue(code.isValid(nowMs = 1_000_000L))
        assertTrue(code.isValid(nowMs = 1_000_000L + 5 * 60 * 1000 - 1))
    }

    @Test
    fun expires_at_TTL_boundary_plus_one() {
        val code = PairingCode(digits = "482193", issuedAtMs = 0L)
        // Spec: "5 minutes" — exactly at 5*60*1000 we consider expired (strict
        // less-than window). Anything earlier is still valid.
        assertTrue(code.isValid(nowMs = 5 * 60 * 1000 - 1))
        assertFalse(code.isValid(nowMs = 5 * 60 * 1000))
        assertFalse(code.isValid(nowMs = 5 * 60 * 1000 + 1))
    }

    @Test
    fun future_clock_drift_is_not_valid() {
        // If a code's issuedAtMs is in the "future" relative to nowMs, either
        // the relay's clock jumped backwards or a bug — reject so we fail loud.
        val code = PairingCode(digits = "482193", issuedAtMs = 1_000L)
        assertFalse(code.isValid(nowMs = 999L))
    }

    @Test
    fun rejects_non_six_digit_strings() {
        assertFailsWith<IllegalArgumentException> {
            PairingCode(digits = "12345", issuedAtMs = 0L) // 5 chars
        }
        assertFailsWith<IllegalArgumentException> {
            PairingCode(digits = "1234567", issuedAtMs = 0L) // 7 chars
        }
        assertFailsWith<IllegalArgumentException> {
            PairingCode(digits = "12345a", issuedAtMs = 0L) // non-digit
        }
    }

    @Test
    fun accepts_leading_zero_six_digits() {
        val code = PairingCode(digits = "000042", issuedAtMs = 0L)
        assertEquals("000042", code.digits)
    }
}
