## Context

- 本 change 的完整背景、实验证据、替代方案评估见 `docs/adr/0004-approval-bridge-via-pretooluse-hook.md`。
- OpenSpec 真相源尚未涉及实际生产代码——`shared/` / `relay/` 仅含 placeholder——因此 spec 修正优先于 RED 测试，不需要迁移已有产品行为。
- 真实 CC 行为证据：`shared/src/commonTest/resources/fixtures/real_captures/` 下 3 个 capture（见 `fixtures/README.md`）。

## Goals / Non-Goals

### Goals
- 让 `openspec/specs/protocol/spec.md` 与 CC 2.1.117 实际 stream-json 输出一致，后续 `CCEventTest` 有真实 fixture 可测。
- 让 `openspec/specs/approval-inbox/spec.md` 的 bridge 机制与唯一可行的拦截点（`PreToolUse` hook）对齐。
- 保留 `approval-inbox` 的**用户可见行为**不变：Inbox 列表、超时 DENY、Allow-Always 策略、桌面 fallback。

### Non-Goals
- 不重写 `terminal` / `file-sync` / `pairing`。
- 不定义 `relay-cli` 的完整 CLI 表面，仅锁定 `approval-bridge` 子命令的契约。
- 不规定 IPC 具体实现（UNIX socket vs TCP vs shared memory）——那是 W1.4 的 design.md 决定的。
- 不修改 coverage 门槛 / TDD 约束。
- 不修改 `protocol` 中与审批无关的部分（WireMessage sealed 体系、单连接、1007/1013 关闭码等）。

## Decisions

### Decision 1: Hook-driven bridge 取代 stream interception
**What**: `ApprovalBridge` 以一个长期运行的本地 IPC server 形态存在；CC 的 `PreToolUse` hook 只是一个 thin shim 子进程，负责把 hook payload 转给 IPC server 并把决策转回 stdout。
**Why**: 这是 CC 二进制给出的唯一 out-of-process 拦截点；hook 的 stdin/stdout 协议由 CC 预定义，relay 只需遵循。
**Alternatives considered**:
- 集成 `@anthropic-ai/claude-agent-sdk` (TS/Python) — 打破 "relay = 单语言 JVM jar" 原则。拒绝。
- 用 `--dangerously-skip-permissions` + 外部 guard — 绕过权限等于砍掉产品核心。拒绝。
- fork `claude` CLI — 违反条款 + 运维负担无限。拒绝。

### Decision 2: 协议层暴露 `HookStarted` / `HookResponse`，但不暴露 `PermissionDecision`
**What**: `CCEvent.HookStarted` 与 `CCEvent.HookResponse` 进入协议 sealed 体系；`PermissionDecision` **不**出现在 CCEvent 里，因为它是 hook 子进程自己产生的 stdout，不走 `claude` 主进程的 stream。
**Why**: 保持 `CCEvent` 仅描述 CC 主进程可观察事件；hook 子进程的通信由 relay 内部 IPC 消息类型（非 CCEvent）表示。避免协议层泄漏实现细节。

### Decision 3: `tool_use` 的关联通过紧邻性与 `tool_use_id`
**What**: `PreToolUse` hook 的 payload 包含 `tool_use_id`；同一 `tool_use_id` 的 `tool_use` 块在 assistant 消息里早于 hook 事件出现，relay 用 id 做 join。
**Why**: stream 是严格时序的，join 仅基于 id；不需要任何时间窗口启发式。
**Observed in**: real_captures/03 的 `hook_started.hook_event=PreToolUse` 与先行 `assistant.content[tool_use].id` 一致。

### Decision 4: 超时触发点从 stream 侧转到 hook 侧
**What**: 超时 10 分钟的计时**起点**不变（审批进入 `approvals` 表的 `proposed_at`），但**终止动作**从 "向 CC stdin 写 `permission_response`" 改为 "让阻塞中的 `relay-cli approval-bridge` 子进程以 DENY 决策退出"。对 CC 而言效果等价（工具被拒绝继续执行）。
**Why**: 正确性同构，实现路径符合 hook 模型。

## Risks / Trade-offs

| Risk | Impact | Mitigation |
|---|---|---|
| CC 未来版本把 `permission_request` 重新放回 stdout stream | 中 | 保留合成 fixture 作为 `CCEventTest.handlesLegacyAssumedSchema` 反例测试；新增后可快速扩展 sealed 变体 |
| Hook 子进程因 relay 主进程崩溃而僵死 > 10 min | 高 | hook shim 内嵌 `CLAUDE_HOOK_TIMEOUT` 默认 10min 硬超时；超时退出码约定 DENY |
| 并发 `PreToolUse` 调用（多工具同时发生） | 中 | IPC server 按 `tool_use_id` 串行排队；W1.4 design.md 定义 worker pool 细节 |
| 用户手工改 `.claude/settings.local.json` 后 hook 注册丢失 | 中 | relay 启动时 idempotent 检查并 merge 注册，缺失则补；记录 audit log |
| hook payload schema 随 CC 版本变化 | 中 | `CCEvent.HookStarted` 用 `raw: JsonObject` 携带整份 payload；只有已声明字段走类型化通道，其余保持 raw |

## Migration Plan

零迁移。W0 commit 中尚无生产实现依赖旧假设；spec 修正后直接作为 W1.1 RED 基线。
archive 既有 `bootstrap` 已完成（`openspec/changes/archive/2026-04-22-bootstrap/`），本 change apply 后会再次 `openspec archive fix-approval-bridge-mechanism`，合并进 `openspec/specs/**`。

## Open Questions

- [ ] `permissionDecisionReason` 对模型的可见性（不阻塞合并）
- [ ] relay hook 注入路径（运行时 `--settings` JSON vs 项目 `.claude/settings.local.json`）— 延到 W1.4 design.md
- [ ] hook 超时的硬编码值（10 分钟）是否来自 relay 配置——延到 W1.4 design.md
