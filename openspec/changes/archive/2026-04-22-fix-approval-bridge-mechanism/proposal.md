# Change: fix-approval-bridge-mechanism — 重构审批桥机制

## Why

W0 收尾时实测 Claude Code 2.1.117 后发现：`claude -p --output-format stream-json`
**不会在 stdout 流中发出 `permission_request` 事件**，bootstrap 阶段写入 `protocol`
与 `approval-inbox` 两个 spec 的核心数据流假设是错的。

官方 Agent SDK 只在 **in-process SDK caller**（`@anthropic-ai/claude-agent-sdk`）
场景下通过 `canUseTool` 回调暴露权限决策；对像本项目这样 shell out 到 `claude`
二进制的父进程，**唯一可行的拦截点是 `PreToolUse` hook**（shell 命令）。

不修正这两个 spec，W1.1 [RED] 的测试和 W1.4 的实现都会偏离真实 CC 行为。

证据：
- `docs/adr/0004-approval-bridge-via-pretooluse-hook.md`（完整架构决策）
- `shared/src/commonTest/resources/fixtures/real_captures/01..03.ndjson`（3 个真实 capture）

## What Changes

- **MODIFIED** capability `protocol`：
  - 删除 "CC 事件编码 → NDJSON `permission_response` 写 stdin" 要求
  - 新增 "Hook 生命周期事件解析"（`CCEvent.HookStarted` / `CCEvent.HookResponse`）
  - 新增 "`tool_use` 块与紧邻 `PreToolUse` hook 的关联"
  - `CCEvent.PermissionResponse` sealed 变体**不再**是协议表面；由 hook 子进程 stdout 替代
- **MODIFIED** capability `approval-inbox`：
  - "relay 拦截 CC stdout `permission_request` 事件" 改为 "relay 注册 `PreToolUse` hook 并通过本地 IPC 阻塞等待手机决策"
  - 决策回写路径从 "写 CC stdin `permission_response`" 改为 "`relay-cli approval-bridge` 子进程 stdout 返回 `hookSpecificOutput.permissionDecision`"
  - 超时、policy、桌面 fallback 行为**保持不变**，只是触发侧改为 hook 超时而非 stdin 写入
- 新增 relay 可执行子命令接口 `relay-cli approval-bridge --session-id <uuid>`（作为 CC `PreToolUse` hook 目标）
- **不** 修改 `terminal` / `file-sync` / `pairing` spec

## Impact

- Affected specs: `protocol`, `approval-inbox`（均为 MODIFIED）
- Affected code:
  - `shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/**`：`CCEvent` sealed 调整
  - `shared/src/commonMain/kotlin/io/ohmymobilecc/features/approval/**`：Interactor 消费的事件源调整
  - `relay/src/main/kotlin/io/ohmymobilecc/relay/cli/**`：新增 `approval-bridge` 子命令
  - `relay/src/main/kotlin/io/ohmymobilecc/relay/approval/**`：Bridge 从 "stream interceptor" 变为 "IPC server + hook installer"
  - `shared/src/commonTest/resources/fixtures/real_captures/03-hook-bridge-approved.ndjson`：W1.1 / W1.4 的黄金测试数据
- Affected docs: 已在 W0 收尾 commit 中随 ADR-0004 同步说明（本 proposal 是其 spec 体现）
- **非破坏性（对 W0 快照而言）**：没有生产代码依赖旧假设；只有 spec 与测试 fixture 受影响
- **对 Plan v2 "Risks" 表**：`CC stream-json permission schema 与假设不符 — 高` 实际触发，缓解由本 change 吸收

## Open Questions / Deferrals

- `PreToolUse` hook 注入方式（写 `.claude/settings.local.json` vs `--settings` 运行时 JSON）留到 W1.4 apply 阶段决定，不在 spec 表面。
- `hookSpecificOutput.permissionDecisionReason` 是否向 CC 显示给模型，尚未在官方文档验证。本 spec 仅要求 relay 发送该字段；模型可见性视 CC 内部实现，不影响我们正确性。
- 单 relay 进程处理并发 hook 调用的线程模型（同一 session_id 串行 vs 并行）属于 W1.4 design.md 范畴，不在本 change 定义。
