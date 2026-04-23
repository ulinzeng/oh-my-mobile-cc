package io.ohmymobilecc.relay.approval

import io.ohmymobilecc.core.protocol.Decision
import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Core invariants of [ApprovalBridge]. Every test uses virtual time
 * via `runTest` so the 10-minute timeout is driven by
 * [advanceTimeBy] rather than wall-clock sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalBridgeTest {
    @Test
    fun `requestApproval emits ApprovalRequested and suspends`() =
        runTest {
            val harness = harness()

            val deferred =
                async {
                    harness.bridge.requestApproval(harness.sampleRequest())
                }
            advanceUntilIdle()

            assertTrue(deferred.isActive, "requestApproval must suspend until decision arrives")
            val emitted = harness.outbound.replayCache.first()
            assertIs<WireMessage.ApprovalRequested>(emitted)
            assertEquals("Bash", emitted.tool)
            assertEquals("S1", emitted.sessionId)

            // Row is in store as PENDING
            val row = harness.store.findById(emitted.approvalId)
            assertNotNull(row)
            assertEquals(ApprovalState.PENDING, row.state)

            // Resolve so runTest doesn't leak the coroutine.
            harness.bridge.submitDecision(emitted.approvalId, Decision.DENY, null)
            deferred.await()
        }

    @Test
    fun `submitDecision ALLOW_ONCE resumes caller with allow`() =
        runTest {
            val harness = harness()

            val deferred = async { harness.bridge.requestApproval(harness.sampleRequest()) }
            advanceUntilIdle()
            val approvalId = (harness.outbound.replayCache.first() as WireMessage.ApprovalRequested).approvalId

            harness.bridge.submitDecision(approvalId, Decision.ALLOW_ONCE, null)
            val outcome = deferred.await()

            assertEquals("allow", outcome.decision.permissionDecision)
            assertNull(outcome.decision.updatedInput)
            assertEquals(approvalId, outcome.approvalId)
            assertEquals(ApprovalState.ALLOWED_ONCE, harness.store.findById(approvalId)?.state)
        }

    @Test
    fun `submitDecision DENY resumes caller with deny`() =
        runTest {
            val harness = harness()

            val deferred = async { harness.bridge.requestApproval(harness.sampleRequest()) }
            advanceUntilIdle()
            val approvalId = (harness.outbound.replayCache.first() as WireMessage.ApprovalRequested).approvalId

            harness.bridge.submitDecision(approvalId, Decision.DENY, null)
            val outcome = deferred.await()

            assertEquals("deny", outcome.decision.permissionDecision)
            assertEquals(ApprovalState.DENIED, harness.store.findById(approvalId)?.state)
        }

    @Test
    fun `CUSTOMIZE decision carries updatedInput`() =
        runTest {
            val harness = harness()

            val deferred = async { harness.bridge.requestApproval(harness.sampleRequest()) }
            advanceUntilIdle()
            val approvalId = (harness.outbound.replayCache.first() as WireMessage.ApprovalRequested).approvalId

            val customInput = buildJsonObject { put("command", JsonPrimitive("ls /tmp")) }
            harness.bridge.submitDecision(approvalId, Decision.CUSTOMIZE, customInput)
            val outcome = deferred.await()

            assertEquals("allow", outcome.decision.permissionDecision)
            assertEquals(
                "ls /tmp",
                outcome.decision.updatedInput
                    ?.get("command")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(ApprovalState.ALLOWED_CUSTOMIZED, harness.store.findById(approvalId)?.state)
        }

    @Test
    fun `ALLOW_ALWAYS writes policy and subsequent same tool-session short-circuits`() =
        runTest {
            val harness = harness()

            // First approval: user picks ALLOW_ALWAYS.
            val first = async { harness.bridge.requestApproval(harness.sampleRequest()) }
            advanceUntilIdle()
            val firstId = (harness.outbound.replayCache.first() as WireMessage.ApprovalRequested).approvalId
            harness.bridge.submitDecision(firstId, Decision.ALLOW_ALWAYS, null)
            val firstOutcome = first.await()
            assertEquals("allow", firstOutcome.decision.permissionDecision)
            assertEquals(ApprovalState.ALLOWED_ALWAYS, harness.store.findById(firstId)?.state)
            assertTrue(harness.store.policyExists("Bash", "S1"))

            val emissionsBefore = harness.outbound.replayCache.size

            // Second approval for same (tool, session): must short-circuit.
            val second = harness.bridge.requestApproval(harness.sampleRequest(toolUseId = "T2"))
            advanceUntilIdle()

            assertEquals("allow", second.decision.permissionDecision)
            assertEquals(
                emissionsBefore,
                harness.outbound.replayCache.size,
                "policy short-circuit must NOT emit a new ApprovalRequested",
            )
            // Row must still be recorded, as AUTO_ALLOWED.
            val autoRow = harness.store.findById(second.approvalId)
            assertNotNull(autoRow)
            assertEquals(ApprovalState.AUTO_ALLOWED, autoRow.state)
        }

    @Test
    fun `timeout auto-denies and broadcasts ApprovalExpired`() =
        runTest {
            val harness = harness()

            val deferred = async { harness.bridge.requestApproval(harness.sampleRequest()) }
            advanceUntilIdle()
            val approvalId = (harness.outbound.replayCache.first() as WireMessage.ApprovalRequested).approvalId

            // Advance virtual time just past 10 minutes.
            advanceTimeBy(10 * 60 * 1000L + 1_000L)
            advanceUntilIdle()

            val outcome = deferred.await()
            assertEquals("deny", outcome.decision.permissionDecision)
            assertTrue(
                outcome.decision.permissionDecisionReason.contains("timeout", ignoreCase = true),
                "expected timeout reason, got ${outcome.decision.permissionDecisionReason}",
            )
            assertEquals(ApprovalState.EXPIRED, harness.store.findById(approvalId)?.state)

            assertTrue(
                harness.outbound.replayCache.any { it is WireMessage.ApprovalExpired },
                "expected ApprovalExpired broadcast, got ${harness.outbound.replayCache}",
            )
        }

    // --- harness ---

    private class Harness(
        scope: TestScope,
    ) {
        val store = InMemoryApprovalStore()
        val outbound =
            MutableSharedFlow<WireMessage>(
                replay = 32,
                extraBufferCapacity = 32,
            )

        // A TimeSeam that reads virtual time from the test scheduler.
        val time =
            object : TimeSeam {
                override fun now(): Long = scope.testScheduler.currentTime

                override suspend fun delay(millis: Long) {
                    kotlinx.coroutines.delay(millis)
                }
            }

        val bridge = ApprovalBridge(store = store, outbound = outbound, time = time, scope = scope.backgroundScope)

        fun sampleRequest(
            sessionId: String = "S1",
            toolName: String = "Bash",
            toolUseId: String = "T1",
            toolInput: JsonObject = buildJsonObject { put("command", JsonPrimitive("ls")) },
        ): BridgeRequest =
            BridgeRequest(
                sessionId = sessionId,
                toolName = toolName,
                toolUseId = toolUseId,
                toolInput = toolInput,
                hookPayload = buildJsonObject { put("tool_use_id", JsonPrimitive(toolUseId)) },
            )
    }

    private fun TestScope.harness(): Harness = Harness(this)
}
