# tasks.md — fix-approval-bridge-mechanism

> 本 change 仅修正 spec；不含生产代码实现。代码层调整将由 Phase W1 的后续 change proposal 承担（例如 `add-wire-protocol-round-trip`、`add-approval-bridge-hook`）。

## 1. Spec 修正

- [ ] 1.1 提案审阅：`proposal.md` + `design.md` 与 ADR-0004 自洽（无矛盾）
- [ ] 1.2 `specs/protocol/spec.md` delta：MODIFIED "CC 事件编码"、ADDED "Hook 生命周期事件解析" 与 "Tool use 与 PreToolUse hook 的关联"
- [ ] 1.3 `specs/approval-inbox/spec.md` delta：MODIFIED "CC 权限事件拦截"、"决策回写"、"超时自动拒绝"、"桌面 fallback"；ADDED "Hook 子进程契约"、"决策 payload 的向前兼容"
- [ ] 1.4 `openspec validate fix-approval-bridge-mechanism --strict` 通过

## 2. 合规门槛

- [ ] 2.1 `fixtures/real_captures/03-hook-bridge-approved.ndjson` 中的事件类型与本 proposal 声明一致（hook_started / hook_response / tool_use 都能覆盖）
- [ ] 2.2 `docs/adr/0004-approval-bridge-via-pretooluse-hook.md` 与本 change 的"Consequences" 与 spec delta 一一对齐
- [ ] 2.3 无破坏性：`openspec/specs/terminal`、`file-sync`、`pairing` 未被本 change 触碰

## 3. 审查与归档

- [ ] 3.1 人类/Agent review：与 Plan v2 Phase W1 前置条件匹配
- [ ] 3.2 合并到 main（建议 --no-ff；可与现有 `feat/w0-closeout` 合并一并处理）
- [ ] 3.3 `openspec archive fix-approval-bridge-mechanism --yes` → 合并 delta 到 `openspec/specs/**`
- [ ] 3.4 归档后再次 `openspec validate --specs --strict` 验证 5 specs 全绿

## 4. 后续（不在本 change 内）

- 4.1 新开 change `add-wire-protocol-round-trip`：W1.1 [RED] 对 CCEvent / WireMessage 的 round-trip + Unknown fallback 写测试
- 4.2 新开 change `add-approval-bridge-hook`：W1.4 [RED+GREEN] ApprovalBridge（含 `relay-cli approval-bridge` 子命令）
