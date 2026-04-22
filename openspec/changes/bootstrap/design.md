# design.md — bootstrap

## Context

一期（W0–W4）必须同时覆盖移动客户端、桌面 relay、自绘终端三条主线。5 个 capability
的边界需要在动工前锁定，避免 W1/W2 并行时产生协议/状态冲突。

## Goals / Non-Goals

- **Goals**：每个 capability 有至少一条可测试的 requirement + scenario；requirement 足够具体让后续 Phase 的 [RED] test 能直接绑定
- **Non-Goals**：不在此 change 中定义 UI/视觉；不落实现代码；不做技术选型（选型在 `docs/adr/`）

## Decisions

- **Decision**: 5 个 capability 一次性声明，而非每 Phase 再补。
  **Rationale**: 避免 W2 Inbox 的 wire 协议变更传导到 W3 终端 capability；也让 `openspec validate --strict` 在 W0 结束就能绿灯。
- **Decision**: `protocol` 与 `approval-inbox` 分为两个 capability，虽然 approval 事件确实跑在 wire 协议上。
  **Rationale**: `protocol` 描述**传输格式**（NDJSON、WireMessage 基类），`approval-inbox` 描述**用户行为**（超时、策略、Inbox UI 契约）。拆分后边界清晰。
- **Decision**: `pairing` 不并入 `protocol`。
  **Rationale**: 握手是一次性事件，具备独立安全属性（Ed25519、一次性码），与稳态传输协议无强耦合。

## Alternatives considered

- **单一 `core` capability 囊括全部**：拒绝，违反 OpenSpec "capability = narrow truth slice" 原则；未来改一处要重写整个 spec
- **延后到每 Phase 开始时才写 spec delta**：拒绝，W2 开始时 W1 已实装，无法在事后反向推导契约
- **把 `terminal` 拆成 `ansi-parser` + `terminal-render` 两个 capability**：延后；W3 若证明 render 层可替换（xterm.js 降级），再拆分

## Risks / Trade-offs

- **风险**：5 份初版 spec 可能过于粗粒度，W1 [RED] 测试需要更细的 requirement → Mitigation：每个 capability 保留 `## MODIFIED Requirements` 的口子，后续 change 专门细化
- **风险**：`approval-inbox` 的 10 分钟超时硬编码在 spec 里会让调参变更成为 breaking change → Mitigation：在 requirement 文案中写 "at least 10 minutes"，数值写在 ADR-0003
