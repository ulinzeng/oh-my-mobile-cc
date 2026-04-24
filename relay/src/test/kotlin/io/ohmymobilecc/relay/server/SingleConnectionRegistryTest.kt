package io.ohmymobilecc.relay.server

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SingleConnectionRegistryTest {
    @Test fun `claim returns false on second claim`() =
        runBlocking {
            val reg = SingleConnectionRegistry()
            assertEquals(true, reg.claim("S1", token = 1))
            assertEquals(false, reg.claim("S1", token = 2))
            reg.release("S1", token = 1)
            assertEquals(true, reg.claim("S1", token = 3))
        }

    @Test fun `release with wrong token is no-op`() {
        val reg = SingleConnectionRegistry()
        reg.claim("S1", token = 1)
        reg.release("S1", token = 99) // wrong
        assertEquals(false, reg.claim("S1", token = 2))
    }

    @Test fun `release unknown session returns silently`() {
        val reg = SingleConnectionRegistry()
        reg.release("nope", token = 0)
        assertNull(reg.currentToken("nope"))
    }
}
