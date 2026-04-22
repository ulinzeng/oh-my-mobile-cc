# protocol — 传输协议修正（delta）

## MODIFIED Requirements

### Requirement: CC 事件编码
系统 SHALL **不** 通过 NDJSON 写 CC stdin 的方式回写权限决策。
权限决策由 CC `PreToolUse` hook 子进程的 stdout 承载（见 `approval-inbox` spec），
不在 `claude` 主进程 stdin 流中出现。

系统 SHALL 仍支持向 CC stdin 写入 **非权限类** 的 `stream-json` 消息（如用户后续轮次的
`{"type":"user","message":{...}}`），编码规则保持：一行 JSON + `\n` + flush。

#### Scenario: 非权限事件编码
- **WHEN** 用户在 Chat 界面发送后续消息
- **THEN** relay 以单行 JSON + `\n` + flush 写入 CC stdin

#### Scenario: 禁止权限 NDJSON 写入
- **WHEN** 任意 relay 代码路径尝试向 CC stdin 写 `{"type":"permission_response",...}`
- **THEN** 代码审查 SHALL 拒绝该变更（该路径不是合法的 CC stream-json 输入事件类型）

## ADDED Requirements

### Requirement: Hook 生命周期事件解析
系统 SHALL 将 CC stream-json 中的 `{"type":"system","subtype":"hook_started"|"hook_response"|"hook_progress",...}` 事件解析为类型化 `CCEvent.HookStarted` / `CCEvent.HookResponse` / `CCEvent.HookProgress`。
所有 hook 事件 SHALL 保留完整原始 `raw: JsonObject`，以容忍 CC 未来的字段扩展。

#### Scenario: PreToolUse hook 生命周期对
- **WHEN** CC stdout 先后出现
  `{"type":"system","subtype":"hook_started","hook_name":"PreToolUse:Bash","hook_event":"PreToolUse","hook_id":"H1","uuid":"U1","session_id":"S1"}`
  与对应的 `hook_response` 行（同 `hook_id`）
- **THEN** 解析为 `CCEvent.HookStarted(hookId="H1", hookName="PreToolUse:Bash", hookEvent="PreToolUse", sessionId="S1", raw=...)`
  与 `CCEvent.HookResponse(hookId="H1", output="...", exitCode=0, outcome="success", raw=...)`

#### Scenario: 未知 hook_event 的 fallback
- **WHEN** 一行 JSON 含 `{"type":"system","subtype":"hook_started","hook_event":"FutureEvent",...}`
- **THEN** 解析为 `CCEvent.HookStarted` 且 `hookEvent="FutureEvent"` 原样保留，不抛异常

### Requirement: Tool use 与 PreToolUse hook 的关联
系统 SHALL 能把 assistant 消息中 `content[].type=="tool_use"` 的块按 `tool_use_id` 与
紧随其后的 `PreToolUse` hook 事件关联起来。关联 SHALL 仅基于 `tool_use_id`（CC 保证唯一），**不** 依赖时间窗口或序号启发式。

#### Scenario: Bash tool_use → PreToolUse 关联
- **WHEN** 一行 JSON 含 `{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","id":"tooluse_X","input":{"command":"ls"}}]}}`
- **AND** 紧接的 `PreToolUse` hook 事件 payload 的 `tool_use_id == "tooluse_X"`
- **THEN** relay SHALL 把二者 join 为一条 `ToolInvocation(toolName="Bash", toolUseId="tooluse_X", input={"command":"ls"})` 逻辑记录

#### Scenario: 仅 tool_use 无 hook（hook 未注册）
- **WHEN** 出现 `tool_use` 块但无任何 `PreToolUse` hook 事件
- **THEN** relay SHALL 跳过审批路径；CC 将按 permission mode 默认规则处置（可能 auto-deny），此时 relay 不生成 Inbox 卡片
