package io.ohmymobilecc

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun `shared marker constant is present`() {
        assertEquals("oh-my-mobile-cc:shared", SHARED_MODULE_MARKER)
    }
}
