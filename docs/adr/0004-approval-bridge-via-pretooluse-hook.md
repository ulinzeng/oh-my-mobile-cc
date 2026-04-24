---
status: accepted
date: 2026-04-22
depends-on: [0003]
---

# ADR-0004 审批桥基于 `PreToolUse` hook 而非 stdout `permission_request` 流事件

- **状态**：Accepted（推翻 Plan v2 中 `permission_request` 流事件假设）
- **日期**：2026-04-22
- **决策者**：项目发起人 + 主 Claude agent（W0 收尾实地验证后）
- **相关文件**：
  - `openspec/specs/protocol/spec.md`（需修正）
  - `openspec/specs/approval-inbox/spec.md`（需修正）
  - `shared/src/commonTest/resources/fixtures/README.md`（已更新）
  - `shared/src/commonTest/resources/fixtures/real_captures/*.ndjson`（已入库）
  - 外部文档：[Claude Agent SDK - Permissions](https://code.claude.com/docs/en/agent-sdk/permissions)、
    [Handle approvals and user input](https://code.claude.com/docs/en/agent-sdk/user-input)

## Context（背景）

Plan v2 W0 阶段基于间接文档猜测制定了如下数据流：

```
claude -p stream-json 输出:
  {"type":"permission_request","tool":"Bash","input":{...},...}
                    │
                    ▼
relay/approval/ApprovalBridge  ← 从 stdout 流中拦截
                    │
                    ▼
relay 写回 claude stdin:
  {"type":"permission_response","request_id":"...","decision":"allow"}
```

W0.6 合成 fixture `permission_bash_request.ndjson` 就是照这个假设写的。

**W0 收尾时在 CC 2.1.117 上实地验证：该假设与现实不符。**
我们执行了三次真实 capture（见 `shared/src/commonTest/resources/fixtures/real_captures/`）：

| Capture | 条件 | stdout 是否出现 `permission_request` | 结果 |
|---|---|---|---|
| 01 | `-p`, text 输入, 默认 permission-mode | ❌ 无 | Bash 被**静默 auto-deny**，仅在最终 `{"type":"result","permission_denials":[...]}` 出现 |
| 02 | `-p`, stream-json 双向, 默认 permission-mode | ❌ 无 | 同上 |
| 03 | `-p`, stream-json 双向, **注册 `PreToolUse:Bash` hook** | ❌ 无 `permission_request`，但有完整 `hook_started` / `hook_response` 流 | Bash **由 hook 批准后执行** |

官方 SDK 文档（2026-04-22）明确声明权限决策走两条路：
1. **`canUseTool` 回调**：只在 in-process SDK caller（Python/TS `@anthropic-ai/claude-agent-sdk`）
   可用；对 out-of-process 的 `claude` 二进制调用者**不可见**。
2. **`PreToolUse` hook**（shell 命令）：注册在 `.claude/settings.json`，每次工具调用
   前被 CC spawn，通过 stdout JSON 返回 `permissionDecision`。用
   `--include-hook-events` 可把 hook 的 `hook_started` / `hook_response` 事件
   流到 CC stdout 供父进程观察。

我们的 relay 既然走的是 shell out 到 `claude -p`，**唯一可行的通用拦截点就是 `PreToolUse` hook**。

## Decision（决策）

一期 `ApprovalBridge` 基于 **`PreToolUse` hook + 本地 IPC** 实现：

1. **relay 启动时**，生成一个临时 `settings.json`（或在用户项目 `.claude/settings.local.json` 中注入），
   注册 `PreToolUse` hook：
   ```json
   {
     "hooks": {
       "PreToolUse": [
         {
           "matcher": "*",
           "hooks": [
             { "type": "command",
               "command": "relay-cli approval-bridge --session-id $CLAUDE_SESSION_ID" }
           ]
         }
       ]
     }
   }
   ```
2. **CC 调用工具前**，spawn `relay-cli approval-bridge`，通过 stdin 向其传递
   `{"session_id", "tool_name", "tool_input", "tool_use_id", ...}`（CC hook 约定的 payload）。
3. `relay-cli approval-bridge` 是 relay 进程的一个**轻量子命令**：
   - 通过 UNIX socket（或 127.0.0.1 localhost 端口）把请求转发给主 relay 进程
   - 主 relay 生成 `approvalId`，落库到 SQLite，推送 `WireMessage.ApprovalRequested` 给手机
   - 阻塞等待决策（超时 10 分钟，期满 DENY）
   - 把决策以 CC 约定的 JSON 写回 stdout：
     ```json
     {
       "hookSpecificOutput": {
         "hookEventName": "PreToolUse",
         "permissionDecision": "allow",  // 或 "deny"
         "permissionDecisionReason": "approved by mobile user jdoe at 14:32"
       }
     }
     ```
   - 以 `exit 0` 结束
4. **移动端决策消息 `WireMessage.ApprovalResponded`** 仍然存在，依旧由 interactor
   消费；唯一改变的是：它不写到 `claude` 的 stdin，而是把结果回复给阻塞中的
   `relay-cli approval-bridge` 子进程（通过 IPC / shared state）。
5. **relay 启动 `claude -p` 时必须带 `--include-hook-events`**，否则它无法观察 CC 在 hook 等待期间的生命周期信号（例如 CC 被用户 Ctrl+C 导致 hook 僵死）。

## Consequences（影响）

### 对 spec 的修正

- `openspec/specs/protocol/spec.md`：删除 `CCEvent.PermissionRequest` 作为流事件的假设；新增
  `CCEvent.HookStarted`（`hook_event = PreToolUse`）+ `CCEvent.HookResponse` 的解析要求。
  `tool_name + tool_input` 由紧邻的 `assistant.message.content[tool_use]` 块提供。
- `openspec/specs/approval-inbox/spec.md`：把 "relay 拦截 `permission_request` 并写回
  `permission_response` 到 stdin" 替换为 "relay 注册 `PreToolUse` hook 并通过本地 IPC
  阻塞等待手机决策"。超时策略、policy 缓存、fallback 全部保留。

这两个修正要在 W1 开启前作为独立的 OpenSpec change 提出（`fix-approval-bridge-mechanism`），
不在 `bootstrap` 归档内追改。

### 对代码结构的影响

- relay 新增一个可独立分发的二进制子命令 `relay-cli approval-bridge`。
  推荐把它和 relay 主进程打在同一 Shadow fat jar（shadow 9.3.1 已配置），通过
  `main` 参数路由。
- shared 侧 `core/protocol/CCEvent.kt` 去掉 `PermissionRequest` 变体，改为 `HookStarted / HookResponse`。
- `ApprovalBridgeTest` 以 `real_captures/03-hook-bridge-approved.ndjson` 为唯一真实数据源。
  合成 fixture 仅用于 `CCEventTest.handlesLegacyAssumedSchema` 作为反例测试（"如果未来
  CC 添加真 `permission_request` 事件，我们不 panic"）。

### 对风险表的影响

Plan v2 "Risks" 表里原有的 "CC stream-json permission schema 与假设不符 — 高" 现在实地
发生了，缓解已完成（本 ADR + 真实 capture）。该风险降级为 "medium: W1.4 实现偏离代码
骨架 30–50%"，由本次 closeout 提交的 spec patch 提案吸收。

## 替代方案（被拒绝）

- **直接集成 `@anthropic-ai/claude-agent-sdk`（TS SDK）**：能拿到真 `canUseTool` 回调，
  但需要 relay 用 Node.js 或通过 JVM 跨进程桥接（GraalJS / jna）。会把 relay 从
  "轻量 JVM CLI" 变成 "双语言分发包"，与 Plan v2 "relay 走 Kotlin/JVM + shadow jar"
  原则相左。拒绝。
- **fork `claude` CLI 或拦截其内部 IPC**：违反 Anthropic 条款，维护成本无限。拒绝。
- **用 `--dangerously-skip-permissions` 完全绕过**：一期核心卖点就是审批，这等于把
  产品杀掉。拒绝。

## 后续动作

1. **开新 change proposal** `fix-approval-bridge-mechanism`：
   - MODIFIED `protocol`：删 `PermissionRequest`，加 `HookStarted / HookResponse`
   - MODIFIED `approval-inbox`：改 bridge 机制
2. W1.1 RED 测试按**修正后**的 schema 写；不再从合成 fixture 派生任何生产结构。
3. `.claude/settings.local.json` 本项目开发侧也注册一个 no-op `PreToolUse` 来模拟 relay 注入，便于本地 relay 集成测试。
