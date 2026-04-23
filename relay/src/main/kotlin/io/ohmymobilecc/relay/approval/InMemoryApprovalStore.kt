package io.ohmymobilecc.relay.approval

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory adapter for [ApprovalStore], backed by
 * [ConcurrentHashMap]s. Used in W1.4 before the SqlDelight adapter
 * lands in W2.3. Tests in `InMemoryApprovalStoreTest` also serve as
 * the contract tests for any future adapter.
 */
public class InMemoryApprovalStore : ApprovalStore {
    private val rows = ConcurrentHashMap<String, ApprovalRow>()
    private val policies = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    override suspend fun insertPending(row: ApprovalRow) {
        val previous = rows.putIfAbsent(row.approvalId, row)
        check(previous == null) { "duplicate approvalId: ${row.approvalId}" }
    }

    override suspend fun updateState(
        approvalId: String,
        state: ApprovalState,
        decisionJson: String?,
    ) {
        val current = rows[approvalId] ?: error("no approval row for id=$approvalId")
        rows[approvalId] = current.copy(state = state, decisionJson = decisionJson)
    }

    override suspend fun findById(approvalId: String): ApprovalRow? = rows[approvalId]

    override suspend fun upsertPolicy(
        tool: String,
        sessionId: String,
    ) {
        policies.add(tool to sessionId)
    }

    override suspend fun policyExists(
        tool: String,
        sessionId: String,
    ): Boolean = policies.contains(tool to sessionId)
}
