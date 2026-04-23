package io.ohmymobilecc.relay.approval

import kotlinx.serialization.json.JsonObject

/**
 * Lifecycle states of an approval request, per
 * `openspec/specs/approval-inbox/spec.md`.
 *
 * Initial state: [PENDING]. Terminal states: everything else.
 */
public enum class ApprovalState {
    PENDING,

    /** User granted once from mobile; no policy written. */
    ALLOWED_ONCE,

    /** User granted and asked to remember — a row was added to `approval_policies`. */
    ALLOWED_ALWAYS,

    /** User granted after editing `tool_input`. */
    ALLOWED_CUSTOMIZED,

    /** User denied. */
    DENIED,

    /** 10-minute clock expired or bridge subprocess crashed before decision. */
    EXPIRED,

    /** Hit an existing `approval_policies` row; never shown to mobile. */
    AUTO_ALLOWED,
}

/**
 * Persisted shape of a single approval request. The spec also has a
 * `decisionJson TEXT` column for forward-compat — we honor that here
 * even before SqlDelight lands, so the in-memory adapter and the
 * future DB adapter share the exact same row type.
 */
public data class ApprovalRow(
    val approvalId: String,
    val sessionId: String,
    val toolUseId: String,
    val toolName: String,
    val inputJson: JsonObject,
    val proposedAt: Long,
    val state: ApprovalState,
    val decisionJson: String?,
)

/**
 * Port interface for approval persistence. W1.4 ships the
 * [InMemoryApprovalStore] adapter; W2.3 will ship the SqlDelight one.
 *
 * Methods are `suspend` even when the in-memory adapter doesn't need
 * it — keeping the signature parity avoids gratuitous churn when the
 * DB adapter lands.
 */
public interface ApprovalStore {
    public suspend fun insertPending(row: ApprovalRow)

    public suspend fun updateState(
        approvalId: String,
        state: ApprovalState,
        decisionJson: String?,
    )

    public suspend fun findById(approvalId: String): ApprovalRow?

    public suspend fun upsertPolicy(
        tool: String,
        sessionId: String,
    )

    public suspend fun policyExists(
        tool: String,
        sessionId: String,
    ): Boolean
}
