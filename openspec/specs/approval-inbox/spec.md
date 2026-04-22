# approval-inbox Specification

## Purpose
提供移动端 **应用内** 审批能力（"Inbox 模式"），替代桌面端 CC 弹窗。
用户在 iOS/Android app 中收到 CC 发出的 `permission_request`，可 Allow / Deny / Customize，
决定通过 relay 回写到 `claude -p` 的 stdin。本 capability 明确 **不** 依赖 APNs：
10 分钟无响应自动 DENY + `expired`，桌面 CC 可作为 fallback 手动确认。
## Requirements
### Requirement: CC 权限事件拦截
系统 SHALL 在 relay 端拦截 CC stream-json 中的 `permission_request` 事件，分配 UUID `approvalId` 并持久化到 SQLite `approvals` 表。

#### Scenario: 首次权限请求
- **WHEN** CC 输出 `{"type":"permission_request","tool":"Bash",...}`
- **THEN** relay 写入 `approvals` 表一行 `(approvalId, sessionId, tool, input_json, state='PENDING', proposed_at)`，并向所有订阅的移动 WS 广播 `WireMessage.ApprovalRequested`

### Requirement: 决策回写
系统 SHALL 接受来自移动端的 `ApprovalResponded` 决策，并在回写 CC stdin 成功后将 `approvals.state` 更新为终态。

#### Scenario: 允许本次
- **WHEN** 用户在手机点 "Allow Once" → 客户端发送 `ApprovalResponded(approvalId, ALLOW_ONCE)`
- **THEN** relay 写入 CC stdin `permission_response` 并把 `approvals.state` 置为 `ALLOWED_ONCE`

### Requirement: 超时自动拒绝
系统 SHALL 在审批请求 `proposed_at` 起 **at least 10 minutes** 后仍未被响应时，自动发送 `approval.expired` 并以 DENY 语义回写 CC，`approvals.state` 置为 `EXPIRED`。

#### Scenario: 离线超时
- **WHEN** 手机 app 在后台、无人响应审批 10 分钟
- **THEN** relay 广播 `WireMessage.ApprovalExpired(approvalId, reason="timeout")`，并向 CC stdin 写入 `permission_response{decision:"deny"}`

### Requirement: Allow-Always 策略
系统 SHALL 支持用户选择 "Allow Always for tool+session"，把 `(tool, sessionId)` 元组写入 `approval_policies` 表，后续同元组的审批请求 SHALL 自动通过、**不** 出现在 Inbox 列表中但仍记入审计日志。

#### Scenario: 策略命中
- **WHEN** `approval_policies` 已存在 `(Bash, session_X)`
- **AND** CC 对 `session_X` 再次请求 Bash 权限
- **THEN** relay 直接回写 `allow`，Inbox 不可见此条，但 `approvals` 表记录 `state='AUTO_ALLOWED'`

### Requirement: 桌面 fallback
系统 SHALL 保留桌面端手工在 CC 终端响应权限的能力；一旦桌面端先回写，relay SHALL 广播 `ApprovalExpired(approvalId, reason="desktop_responded")`，Inbox 自动移除该卡片。

#### Scenario: 桌面先响应
- **WHEN** 桌面用户在 CC 终端按 Allow，同一时刻手机上 Inbox 显示该卡片
- **THEN** relay 检测到 CC 已继续执行（下一个 assistant 事件到达），广播 expired，移动端移除卡片

