package io.ohmymobilecc.relay.cli

import io.ohmymobilecc.core.pairing.DeviceId
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RevokeCommandTest {
    @Test
    fun `revokes a registered deviceId`() {
        val state = RelayProcessState.fresh()
        val pk = ByteArray(32) { 0x55 }
        val deviceId = DeviceId.fromPublicKey(pk)
        state.registry.register(deviceId, pk, 1_000L)

        val stdout = ByteArrayOutputStream()
        val exit =
            RevokeCommand.run(
                argv = arrayOf(deviceId.raw),
                stdout = PrintStream(stdout, true),
                stderr = PrintStream(ByteArrayOutputStream(), true),
                state = state,
            )
        assertEquals(0, exit)
        val found = state.registry.find(deviceId)
        assertNotNull(found)
        assertNotNull(found.revokedAtMs)
        assertContains(stdout.toString(Charsets.UTF_8), "revoked deviceId=${deviceId.raw}")
    }

    @Test
    fun `unknown id exits 0 with warning and no registry change`() {
        val state = RelayProcessState.fresh()
        val stdout = ByteArrayOutputStream()
        val exit =
            RevokeCommand.run(
                argv = arrayOf("dId_does_not_exist"),
                stdout = PrintStream(stdout, true),
                stderr = PrintStream(ByteArrayOutputStream(), true),
                state = state,
            )
        assertEquals(0, exit)
        assertContains(stdout.toString(Charsets.UTF_8), "warning")
        assertNull(state.registry.find(DeviceId("dId_does_not_exist")))
    }
}
