# approval-inbox — 审批桥机制修正（delta）

## MODIFIED Requirements

### Requirement: CC 权限事件拦截
系统 SHALL 通过向本机 CC 注册 `PreToolUse` hook（matcher `*` 或按工具白名单）来拦截
CC 即将发起的工具调用。注入方式可以是项目级 `.claude/settings.local.json`，或 relay
启动 `claude -p` 时通过 `--settings <json>` 传入；具体路径由 W1.4 design.md 决定。

Hook 目标 SHALL 为 relay 提供的子命令 `relay-cli approval-bridge --session-id <uuid>`，
该子进程从 stdin 读取 CC 约定的 hook payload（包含 `tool_name`, `tool_input`,
`tool_use_id`, `session_id`, `transcript_path` 等字段），通过本地 IPC 把请求转发给
主 relay 进程。

主 relay 进程 SHALL 为每次 hook 调用分配 UUID `approvalId`，落库到 SQLite
`approvals` 表（列：`approvalId`, `sessionId`, `toolUseId`, `toolName`, `inputJson`,
`state='PENDING'`, `proposedAt`），并向所有订阅该 session 的移动 WS 广播
`WireMessage.ApprovalRequested`。

#### Scenario: Bash PreToolUse hook 首次到达
- **WHEN** CC 即将调用 Bash，触发 `PreToolUse:Bash` hook，spawn `relay-cli approval-bridge`
- **AND** 子进程把 payload `{session_id:"S1", tool_name:"Bash", tool_use_id:"T1", tool_input:{command:"ls"}}` 通过 IPC 转给主 relay
- **THEN** 主 relay 插入 `approvals` 行 `(approvalId=A1, sessionId=S1, toolUseId=T1, toolName="Bash", state='PENDING', proposedAt=now)`
- **AND** 向订阅 S1 的移动 WS 广播 `WireMessage.ApprovalRequested(approvalId="A1", sessionId="S1", tool="Bash", input={...}, proposedAt=now)`

#### Scenario: 无活跃移动端订阅
- **WHEN** hook 触发但当前 S1 没有活跃移动 WS 订阅
- **THEN** relay 仍写入 `approvals` 行，手机下次连接时通过 "pending approvals" 同步拉取；同时 hook 子进程继续阻塞直至超时或决策到达

### Requirement: 决策回写
系统 SHALL 接受来自移动端的 `WireMessage.ApprovalResponded(approvalId, decision, customInput?)` 决策。
主 relay 进程 SHALL 把决策通过 IPC 传回对应的 `relay-cli approval-bridge` 阻塞子进程，
子进程 SHALL 在 stdout 输出 CC 约定的决策 JSON 并以 exit code 0 结束：

```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "allow",   // 或 "deny"
    "permissionDecisionReason": "approved by mobile user <id> at <ts>"
  }
}
```

`ALLOW_ONCE` / `ALLOW_ALWAYS` 对 CC 都发送 `permissionDecision=allow`；二者差异仅体现在 relay 本地 `approval_policies` 表是否落一条长期策略。`DENY` 对 CC 发送 `permissionDecision=deny`。`CUSTOMIZE` SHALL 发送 `permissionDecision=allow` 且附带修改后的 `tool_input`（覆盖 CC 原始 input）。

决策回写成功后 `approvals.state` SHALL 更新为 `ALLOWED_ONCE` / `ALLOWED_ALWAYS` / `DENIED` / `ALLOWED_CUSTOMIZED` 对应终态。

#### Scenario: 允许本次
- **WHEN** 用户在手机点 "Allow Once" → 客户端发 `ApprovalResponded(approvalId=A1, decision=ALLOW_ONCE)`
- **THEN** 主 relay 把决策发给阻塞的 bridge 子进程，子进程输出 `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow",...}}` 并 exit 0
- **AND** `approvals.state` 更新为 `ALLOWED_ONCE`

#### Scenario: 带修改允许
- **WHEN** 用户点 "Customize" 并把 `command:"ls /"` 改为 `command:"ls /tmp"` → 客户端发 `ApprovalResponded(decision=CUSTOMIZE, customInput={command:"ls /tmp"})`
- **THEN** bridge 子进程输出决策 JSON 含 `permissionDecision="allow"` 且 `hookSpecificOutput` 内额外附上 `updatedInput:{command:"ls /tmp"}`（按 CC hook 约定字段名）
- **AND** `approvals.state='ALLOWED_CUSTOMIZED'`，`approvals.inputJson` 记录改动前后两份

### Requirement: 超时自动拒绝
系统 SHALL 在审批请求 `proposedAt` 起 **at least 10 minutes** 后仍未收到移动端决策时，
由主 relay 主动让对应的 bridge 子进程以 DENY 决策退出。
具体：主 relay 在 `proposedAt + 10min` 到达时向 bridge 子进程发送 `TIMEOUT_DENY` IPC 命令，
子进程输出 `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"timeout: no mobile response within 10 minutes"}}` 并 exit 0。
主 relay 同时广播 `WireMessage.ApprovalExpired(approvalId, reason="timeout")` 给移动端，`approvals.state='EXPIRED'`。

#### Scenario: 离线超时
- **WHEN** 手机 app 在后台、无人响应审批 10 分钟
- **THEN** 主 relay 向 bridge 子进程发 TIMEOUT_DENY，子进程输出 deny JSON 并退出
- **AND** 主 relay 广播 `ApprovalExpired(approvalId, reason="timeout")`

#### Scenario: bridge 子进程在到期前崩溃
- **WHEN** bridge 子进程被 OS 杀掉（OOM / 用户 kill）先于 10 分钟
- **THEN** 主 relay 侦测到 IPC 断开，状态转 `EXPIRED` 并广播 `ApprovalExpired(approvalId, reason="bridge_lost")`
- **AND** CC 会因 hook 非零退出或无输出按 hook 默认策略处置（通常等价于 deny）

### Requirement: 桌面 fallback
系统 SHALL 保留桌面端响应权限的能力。具体两条 fallback 路径：

1. **用户直接编辑 `.claude/settings.local.json`** 追加 allow 规则，下次工具调用命中 CC 内置 `allow rules`，hook 根本不会被调用（relay 无感知，Inbox 不出现卡片）。
2. **桌面另有一个 relay 直接 approve**（罕见；例如自动化测试）：主 relay 通过 IPC 接到本地 approve 命令，等价于移动端的 `ApprovalResponded(ALLOW_ONCE)`。

**不再** 有 "桌面 CC 终端手工点 Allow" 这一路径——`claude -p` 非交互模式没有交互 UI。
若用户希望桌面参与决策，应该使用路径 1（settings 规则）或直接不启用 Inbox（relay 的 hook 注册是可选的）。

#### Scenario: 桌面预批 Bash
- **WHEN** 用户在 `.claude/settings.local.json` 的 `permissions.allow` 中加入 `"Bash(ls *)"`
- **AND** CC 调用 `ls /tmp`
- **THEN** CC allow rule 先于 hook 命中，relay `PreToolUse` hook **不** 被调用，Inbox 无新卡片

#### Scenario: 本机脚本直接 approve
- **WHEN** 本机运行 `relay-cli approval-admin approve A1`（本地管理命令）
- **THEN** 主 relay 把 A1 转为 `ALLOWED_ONCE`，等价于移动端 `ApprovalResponded`

## ADDED Requirements

### Requirement: Hook 子进程契约
系统 SHALL 提供 `relay-cli approval-bridge` 子命令，签名至少包含：

- 必选：`--session-id <uuid>`（由 hook 配置中注入 `$CLAUDE_SESSION_ID` 或等价占位符提供）
- stdin：接收 CC hook 约定的 JSON payload（单行或按 CC 规范）
- stdout：成功时单行 CC 约定决策 JSON（见 Decision Recode）+ `\n`；失败时空或错误诊断，由调用方决定
- exit code：0 表示决策成功；非 0 表示 bridge 异常（CC 按 hook 失败处理）
- 环境变量：`CLAUDE_HOOK_TIMEOUT_MS`（可选，默认 600000 = 10 分钟），超时则自 DENY 并 exit 0

子进程 SHALL **无状态**：所有持久化在主 relay；本子进程只是 RPC 客户端。

#### Scenario: 正常调用
- **WHEN** CC spawn `relay-cli approval-bridge --session-id S1`，stdin 喂入合法 payload
- **THEN** 子进程阻塞直至决策到达，以单行决策 JSON + `\n` 输出并 exit 0

#### Scenario: 主 relay 不可达
- **WHEN** 子进程尝试连接主 relay IPC 端点失败（socket 不存在 / 连接拒绝）
- **THEN** 子进程以非 0 退出（典型 `2`），stderr 打印诊断；CC 回退到 hook 失败默认行为

### Requirement: 决策 payload 的向前兼容
系统 SHALL 在 `approvals` 表同时保留决策输出的完整 JSON 副本（列 `decisionJson TEXT`），便于 CC 升级后比对 hook 决策 schema 变化。
任何对决策 JSON 结构的扩展 SHALL 通过新的 OpenSpec change 反映到本 spec，不在 `approvals.decisionJson` 字段内无提案变更。

#### Scenario: 升级后 schema diff
- **WHEN** CC 新版引入 `hookSpecificOutput.additionalPermissionContext` 字段
- **THEN** 项目 SHALL 在本 spec 新开 change proposal，迁移 `decisionJson` 解析；现网 relay 仍能工作（raw JSON 存着），新字段忽略直至 schema 升级
