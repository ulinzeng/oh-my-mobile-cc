# Change: W1.5 relay transport (WS server + shared client) + operator CLI

## Why

W1.5 的剩余三件工作（Task 11–13 of `.claude/PRPs/plans/w1.5-pairing-relayclient.plan.md`）把 W1.1–W1.4 已经落地的 **shared wire** 与 **pairing logic** 缝到 **Ktor WebSocket 传输层** 与 **relay-cli 操作入口**：

1. **`RelayServer`** — Ktor plugin 安装 `/ws` 端点，第一帧是 `ClientHello`，通过 `ClientHelloVerifier` 校验；校验通过后桥接 `ApprovalBridge.outbound` → WS outgoing，WS incoming → 外部注入的 `onInbound` 回调。同 `sessionId` 的第二个连接即时拒绝（`HelloErr("duplicate-session")` + 关 1013）。
2. **`KtorRelayClient`** — `shared` 里的传输端口 `TransportPort` 与 Ktor WS 客户端实现。自动用 `Ed25519.sign` 签 hello，等待 `HelloOk` / `HelloErr`，暴露 `incoming: Flow<WireMessage>`。
3. **`relay-cli` 扩展** — `pair` / `revoke` / `serve` 三个子命令，复用 W1.4 已有的 `ApprovalBridgeCommand` 分发骨架。

现有 `pairing` spec 覆盖了 "6 位码、ed25519 签名、replay 防护、取消配对" 的抽象契约，但没有写**具体关闭码、reason 字母表、单连接行为、first-frame 约束**。`protocol` spec 有 "单活跃 WS + 1013 拒绝第二连接"，但没写 first-frame 必须是 `ClientHello`，也没写 `HelloErr.reason` 如何映射到 close code。这些是 W1.5 落地服务端/客户端时必须固化的断言——否则将来换 transport 很容易漂移。

## What Changes

### `pairing` spec (ADDED + MODIFIED)

- **ADDED Requirement: HelloErr reason alphabet**
  - `skew`, `nonce`, `sig`, `unpaired`, `revoked`, `duplicate-session`, `malformed`, `expected-hello` 八个固定值；relay 的 `HelloErr.reason` SHALL 来自其一。
- **ADDED Requirement: 配对取消时活跃连接踢出语义** （落地 W1.5 plan "punt live-evict on revoke to W1.6" 的已知债务约束 — 当前 revoke 产生的连接剔除 MAY 延迟到下次 hello；存量连接的立即踢出明确是 W1.6 工作。写入 spec 防止默默跳过或默默"顺手做了但忘了测"。）
- **ADDED Requirement: relay-cli pair / revoke 子命令**
  - `relay-cli pair` 生成 6 位码、打印到 stdout，等待客户端输入；成功后写入 `PubkeyRegistry` 并打印 deviceId。
  - `relay-cli revoke <deviceId>` 把 registry 的那条 revoked_at 置为 `now`。

### `protocol` spec (MODIFIED + ADDED)

- **MODIFIED Requirement: 传输语义**
  - 保留原来的 "单活跃 WS" + "1013 拒绝第二连接" + "1007 格式/签名错关闭"；**追加** "连接首帧 SHALL 为 `WireMessage.ClientHello`；relay 在首帧非 ClientHello 时回 `HelloErr("expected-hello")` 并以 1008 关闭" + "HelloErr 到 close code 的映射：`skew`/`nonce`/`sig`/`unpaired`/`revoked`/`expected-hello` 均 1008； `duplicate-session` 为 1013；`malformed` 为 1007"。
- **ADDED Requirement: 共享 TransportPort 契约**
  - `shared` 模块 SHALL 暴露 `TransportPort` 接口：`suspend fun connect(endpoint, deviceKey): Result<Session>`，`Session` 提供 `incoming: Flow<WireMessage>` + `suspend fun send(msg: WireMessage)` + `suspend fun close()`。
  - 连接实现 MUST 以 `Ed25519.sign` 对 canonical `sessionId|ts|nonce` 签名,首帧发 `ClientHello`,在 `HelloOk` 之前收到的任何非 `HelloErr` / 非 `HelloOk` 帧 SHALL 以 `RelayError.ProtocolViolation` 上报。
- **ADDED Requirement: relay-cli serve 子命令**
  - `relay-cli serve --port <n>` SHALL 启动一个 Ktor Netty 服务器,安装 `RelayServer` 插件,注入与主 relay 进程共享的 `PubkeyRegistry` / `NonceCache` / `ApprovalBridge.outbound` 实例。

## Impact

- **Affected specs**:
  - `pairing` — 新增 reason 字母表、revoke-live-evict 语义、pair/revoke CLI 要求
  - `protocol` — 修改传输语义（追加首帧约束 + close code 映射）、新增 TransportPort 契约、新增 serve CLI 要求
- **Affected code**:
  - `relay/src/main/kotlin/io/ohmymobilecc/relay/server/` — NEW: `RelayServer.kt`, `SingleConnectionRegistry.kt`
  - `relay/src/main/kotlin/io/ohmymobilecc/relay/cli/RelayCli.kt` — MODIFIED: 分发 `pair` / `revoke` / `serve` 子命令
  - `relay/src/main/kotlin/io/ohmymobilecc/relay/cli/` — NEW: `PairCommand.kt`, `RevokeCommand.kt`, `ServeCommand.kt`
  - `shared/src/commonMain/kotlin/io/ohmymobilecc/core/transport/` — NEW: `TransportPort.kt`, `RelayError.kt`
  - `shared/src/jvmMain/kotlin/io/ohmymobilecc/core/transport/KtorRelayClient.kt` — NEW (jvm actual for W1.5；iOS/Android 在 W2.x)
- **Affected dependencies**:
  - `shared/build.gradle.kts` `jvmMain.dependencies` 新增 `ktor-client-cio` + `ktor-client-websockets`（shared 自己的客户端实现）；已有 `ktor-server-*` 在 relay 足够；detekt baseline 继续沿用项目通用配置。
- **No behavioral change** 到 `pairing/spec.md` 已有的 6 位码 / ed25519 / 10min nonce TTL 约束；它们仍然是本 change 的**假设条件**而非修改目标。
- **Archive 顺序**：此 change **先** archive，`add-ed25519-platform-crypto-impl` 紧随其后（它的 7.4 task 等 W1.5 merge 后一起 archive 更紧凑）。
