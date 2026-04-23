package io.ohmymobilecc.relay.approval

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the [ApprovalStore] port, exercised through its
 * [InMemoryApprovalStore] adapter. The SqlDelight adapter (W2.3) will
 * reuse this same test file on a common-superclass basis.
 */
class InMemoryApprovalStoreTest {
    @Test
    fun `insert then find round-trips`() =
        runTest {
            val store = InMemoryApprovalStore()
            val row = sampleRow()

            store.insertPending(row)
            val got = store.findById(row.approvalId)

            assertEquals(row, got)
        }

    @Test
    fun `find of unknown id returns null`() =
        runTest {
            val store = InMemoryApprovalStore()
            assertNull(store.findById("never-inserted"))
        }

    @Test
    fun `double insert rejects`() =
        runTest {
            val store = InMemoryApprovalStore()
            val row = sampleRow()
            store.insertPending(row)
            assertFailsWith<IllegalStateException> {
                store.insertPending(row)
            }
        }

    @Test
    fun `updateState transitions row and records decision json`() =
        runTest {
            val store = InMemoryApprovalStore()
            val row = sampleRow()
            store.insertPending(row)

            store.updateState(row.approvalId, ApprovalState.ALLOWED_ONCE, """{"ok":true}""")

            val after = store.findById(row.approvalId)!!
            assertEquals(ApprovalState.ALLOWED_ONCE, after.state)
            assertEquals("""{"ok":true}""", after.decisionJson)
            assertEquals(row.proposedAt, after.proposedAt, "proposedAt should be immutable")
        }

    @Test
    fun `updateState for unknown id throws`() =
        runTest {
            val store = InMemoryApprovalStore()
            assertFailsWith<IllegalStateException> {
                store.updateState("missing", ApprovalState.DENIED, null)
            }
        }

    @Test
    fun `policy lookup starts empty`() =
        runTest {
            val store = InMemoryApprovalStore()
            assertFalse(store.policyExists("Bash", "S1"))
        }

    @Test
    fun `upsertPolicy then policyExists returns true`() =
        runTest {
            val store = InMemoryApprovalStore()
            store.upsertPolicy("Bash", "S1")
            assertTrue(store.policyExists("Bash", "S1"))
            assertFalse(store.policyExists("Bash", "S2"))
            assertFalse(store.policyExists("Read", "S1"))
        }

    private fun sampleRow(
        id: String = "A1",
        session: String = "S1",
        tool: String = "Bash",
    ): ApprovalRow =
        ApprovalRow(
            approvalId = id,
            sessionId = session,
            toolUseId = "T1",
            toolName = tool,
            inputJson = sampleInput(),
            proposedAt = 1_710_000_000_000L,
            state = ApprovalState.PENDING,
            decisionJson = null,
        )

    private fun sampleInput(): JsonObject =
        buildJsonObject {
            put("command", JsonPrimitive("ls"))
        }
}
