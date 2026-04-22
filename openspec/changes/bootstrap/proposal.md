# Change: bootstrap — 初始化 5 大 capability

## Why

项目尚无任何 spec 存在。一期（W0–W4）需要一次性锁定 **5 个不变真相**：
`protocol`、`approval-inbox`、`terminal`、`file-sync`、`pairing`。
后续每个功能变更须针对这 5 个 capability 单独开 change proposal。
本次 bootstrap 仅声明初始版本，不落实现。

## What Changes

- **ADDED** capability `protocol`：CC stream-json 事件模型 + WireMessage 移动端协议
- **ADDED** capability `approval-inbox`：移动端审批 Inbox（无 APNs）
- **ADDED** capability `terminal`：VT100/ANSI 解析 + Compose 自绘
- **ADDED** capability `file-sync`：配对后增量文件浏览/编辑
- **ADDED** capability `pairing`：6 位配对码 + Ed25519 签名握手

本次 change **不** 修改代码、**不** 修改 `openspec/project.md`，仅声明 5 个 spec 初版。

## Impact

- Affected specs: `protocol`, `approval-inbox`, `terminal`, `file-sync`, `pairing`（均为新增）
- Affected code: 无（随后各 Phase 变更按需 ADDED 新 requirement 或 MODIFIED）
- 后续 archive 将把这 5 个 delta 搬到 `openspec/specs/<cap>/spec.md`
