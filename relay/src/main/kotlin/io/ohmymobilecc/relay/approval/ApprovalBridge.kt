package io.ohmymobilecc.relay.approval

import io.ohmymobilecc.core.protocol.Decision
import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tuple returned by [ApprovalBridge.requestApproval]: the allocated id + the final CC-shaped decision. */
public data class ApprovalOutcome(
    val approvalId: String,
    val decision: BridgeDecision,
)

/**
 * Orchestrates a single pending-approval's lifecycle from the moment
 * the `PreToolUse` hook subprocess asks the main relay for a decision
 * until either the mobile user answers or the 10-minute timeout fires.
 *
 * Responsibilities:
 *  1. Short-circuit via `approval_policies` when a prior `ALLOW_ALWAYS`
 *     exists for the same `(tool, sessionId)` pair.
 *  2. Otherwise: allocate an `approvalId`, persist the row as
 *     `PENDING`, emit a [WireMessage.ApprovalRequested] on the
 *     outbound flow so the WebSocket layer (W1.5) can forward it to
 *     mobile clients, then suspend until the decision arrives.
 *  3. On [submitDecision] from the WebSocket layer: map the mobile
 *     `Decision` enum to a CC-format [BridgeDecision], advance the row
 *     state, and resume the suspended waiter.
 *  4. On timeout: emit [WireMessage.ApprovalExpired], mark the row
 *     `EXPIRED`, and resume the waiter with a `deny` decision whose
 *     reason mentions the timeout.
 *
 * Threading: every public method is `suspend`; internal state
 * ([waiters]) is a [ConcurrentHashMap] plus a [Mutex] for the
 * narrow "insert waiter + emit + launch timeout" critical section.
 */
public class ApprovalBridge(
    private val store: ApprovalStore,
    private val outbound: kotlinx.coroutines.flow.MutableSharedFlow<WireMessage>,
    private val time: TimeSeam = SystemTimeSeam,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<BridgeDecision>>()
    private val registerLock = Mutex()

    /**
     * Suspend until the mobile user answers, a matching policy
     * short-circuits the request, or [timeoutMillis] elapses.
     */
    public suspend fun requestApproval(req: BridgeRequest): ApprovalOutcome {
        // 1) Policy short-circuit
        if (store.policyExists(req.toolName, req.sessionId)) {
            val autoId = newApprovalId()
            store.insertPending(
                ApprovalRow(
                    approvalId = autoId,
                    sessionId = req.sessionId,
                    toolUseId = req.toolUseId,
                    toolName = req.toolName,
                    inputJson = req.toolInput,
                    proposedAt = time.now(),
                    state = ApprovalState.PENDING,
                    decisionJson = null,
                ),
            )
            val decision =
                BridgeDecision(
                    permissionDecision = "allow",
                    permissionDecisionReason = "auto-allowed by policy",
                )
            store.updateState(autoId, ApprovalState.AUTO_ALLOWED, stringifyDecision(decision))
            return ApprovalOutcome(autoId, decision)
        }

        // 2) Fresh approval: allocate, persist, emit, suspend
        val approvalId = newApprovalId()
        val proposedAt = time.now()
        store.insertPending(
            ApprovalRow(
                approvalId = approvalId,
                sessionId = req.sessionId,
                toolUseId = req.toolUseId,
                toolName = req.toolName,
                inputJson = req.toolInput,
                proposedAt = proposedAt,
                state = ApprovalState.PENDING,
                decisionJson = null,
            ),
        )
        val waiter = CompletableDeferred<BridgeDecision>()
        registerLock.withLock {
            waiters[approvalId] = waiter
        }
        outbound.emit(
            WireMessage.ApprovalRequested(
                approvalId = approvalId,
                sessionId = req.sessionId,
                tool = req.toolName,
                input = req.toolInput,
                proposedAt = proposedAt,
            ),
        )

        // 3) Launch timeout watcher
        scope.launch {
            time.delay(timeoutMillis)
            expireIfStillPending(approvalId)
        }

        return ApprovalOutcome(approvalId, waiter.await())
    }

    /**
     * Route a mobile-originated [Decision] to the suspended request
     * identified by [approvalId]. No-op + warn if the id is unknown
     * or already resolved.
     */
    public suspend fun submitDecision(
        approvalId: String,
        decision: Decision,
        customInput: JsonObject?,
    ) {
        val waiter = waiters.remove(approvalId) ?: return
        val (bridgeDecision, newState) = mapDecision(decision, customInput)
        store.updateState(approvalId, newState, stringifyDecision(bridgeDecision))
        if (decision == Decision.ALLOW_ALWAYS) {
            val row = store.findById(approvalId) ?: return
            store.upsertPolicy(row.toolName, row.sessionId)
        }
        waiter.complete(bridgeDecision)
    }

    private suspend fun expireIfStillPending(approvalId: String) {
        val waiter = waiters.remove(approvalId) ?: return // already resolved
        val reason = "timeout: no mobile response within ${timeoutMillis / MILLIS_PER_SECOND}s"
        val decision =
            BridgeDecision(
                permissionDecision = "deny",
                permissionDecisionReason = reason,
            )
        store.updateState(approvalId, ApprovalState.EXPIRED, stringifyDecision(decision))
        outbound.emit(WireMessage.ApprovalExpired(approvalId = approvalId, reason = "timeout"))
        waiter.complete(decision)
    }

    private fun mapDecision(
        decision: Decision,
        customInput: JsonObject?,
    ): Pair<BridgeDecision, ApprovalState> =
        when (decision) {
            Decision.ALLOW_ONCE ->
                BridgeDecision(
                    permissionDecision = "allow",
                    permissionDecisionReason = "allowed once by mobile user",
                ) to ApprovalState.ALLOWED_ONCE
            Decision.ALLOW_ALWAYS ->
                BridgeDecision(
                    permissionDecision = "allow",
                    permissionDecisionReason = "allowed always by mobile user",
                ) to ApprovalState.ALLOWED_ALWAYS
            Decision.DENY ->
                BridgeDecision(
                    permissionDecision = "deny",
                    permissionDecisionReason = "denied by mobile user",
                ) to ApprovalState.DENIED
            Decision.CUSTOMIZE ->
                BridgeDecision(
                    permissionDecision = "allow",
                    permissionDecisionReason = "customized by mobile user",
                    updatedInput = customInput,
                ) to ApprovalState.ALLOWED_CUSTOMIZED
        }

    private fun newApprovalId(): String = UUID.randomUUID().toString()

    private fun stringifyDecision(d: BridgeDecision): String =
        buildJsonObject {
            put("permissionDecision", JsonPrimitive(d.permissionDecision))
            put("permissionDecisionReason", JsonPrimitive(d.permissionDecisionReason))
            d.updatedInput?.let { put("updatedInput", it) }
        }.toString()

    public companion object {
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 10L * 60L * 1000L // 10 minutes
        private const val MILLIS_PER_SECOND: Long = 1000L
    }
}
