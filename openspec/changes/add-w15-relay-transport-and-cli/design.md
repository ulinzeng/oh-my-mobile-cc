## Context

W1.5 的 Task 0–9 已经落地:Ed25519 (通过 `add-ed25519-platform-crypto-impl`)、hello codec、`PairingCode` + `PairingService` + `PubkeyRegistry` + `NonceCache` + `ClientHelloVerifier`。剩下的 11–13 是把这些组件拼进 **Ktor WS 服务器端** 与 **shared 传输客户端** 并开 **operator CLI 入口**。该层要做的决定不多,但每一条都会长期影响后续 W1.6+(live-session enroll/revoke、iOS transport actual、多客户端 fan-out)。把这些决定写进 spec 前先在本文件盘一下理由。

## Goals / Non-Goals

- **Goals**:
  1. `RelayServer` 是一个 Ktor **plugin**(`RelayServer.install(application, …)`)而非独立 Netty engine,便于 `testApplication` 测试 + 未来把 HTTP 健康检查等路由挂在同一 application。
  2. `SingleConnectionRegistry` 用 **token-based claim/release** 语义,避免 "`client.close()` 慢 / 死 → 后续连接永远被锁" 类问题。
  3. `TransportPort` 放在 `shared/commonMain`,实现在 `jvmMain`(W1.5)→ 后续 W2.x 加 `androidMain` / `iosMain`。iOS actual 在 W2.1 落地时 sharing test vectors,与 ed25519 一致。
  4. 把 HelloErr `reason` alphabet 固定死 — 客户端可以安全 switch-on。
  5. relay-cli `pair` / `revoke` / `serve` 共享 **同一个进程级 state**(`PubkeyRegistry` + `NonceCache` + `ApprovalBridge.outbound`),避免 "CLI 改了 registry 但 serve 没看见" 类的 dev footgun。

- **Non-Goals**:
  1. **Live-evict on revoke** 不在本 change 范围。具体语义:`revoke` 发生时,已握手连接继续活跃到下次 hello 才会被拒。这是已知债,在 spec 里显式写出来(避免"以为做了但没做")。**W1.6 的 change proposal 会反悔这一条** — 届时 `PubkeyRegistry.revoke` 需要一条 `kick: Flow<DeviceId>` 或 `CompletableDeferred<Unit>` sink,被 `RelayServer` 消费。
  2. **iOS / Android actual** 不在本 change,W1.5 只要求 `jvmMain` 有一份 actual。常量契约(HelloErr alphabet、close code 映射)在 spec 里,未来 iOS/Android actual 必须同样实现。
  3. **反向 WS**(relay 反连客户端、NAT 穿透)永远不做 — 项目约束是 Tailscale mesh。
  4. **多 session / fan-out**(同一 deviceId 并发多 session)W1.5 单 session per deviceId 约束不变;fan-out 等终端观察真正在 W3 落地时再开 proposal。

## Decisions

### D1: `SingleConnectionRegistry` 是 token-based claim/release,不是 bool flag

**Decision**: `claim(sessionId, token: Long): Boolean` + `release(sessionId, token: Long)` + `currentToken(sessionId): Long?`。`claim` 在 session 已被占时返回 false。`release` 只有在 `token` 与当前占位者 token 一致时才释放 — token 不对直接 no-op。第二次 `claim` 用**新 token** 补位。

**Alternatives considered**:
- `AtomicBoolean` per session. **Rejected** — 旧连接异常断开没收到 `release`,或 release 发生在 claim 前的 race,都会让后续 claim 永远无法通过。token 不匹配 = 后来者赢。
- `ConcurrentHashMap<String, Job>` 直接存 connection-coroutine。**Rejected** — 把连接生命周期与登记表耦合,测试时必须起真 WS 才能走流程。

### D2: RelayServer 是 `install(application, …)`,不是独立 engine

**Decision**: `object RelayServer { fun install(app: Application, registry, nonceCache, clock, outbound, onInbound) { app.routing { webSocket("/ws") { … } } } }`

**Alternatives considered**:
- 返回 `EmbeddedServer`。**Rejected** — 测试用 `testApplication` 就是绕开 engine 的;再提供 `ServeCommand` 包一层 Netty engine 就够了。
- Ktor Plugin DSL (`createApplicationPlugin`)。**Rejected** — DSL overhead + 不需要 install-order 控制,裸 `install` 函数更短。

### D3: `HelloErr.reason` 是一个固定字母表(8 个值),不是自由文本

**Decision**: reason alphabet = `{skew, nonce, sig, unpaired, revoked, duplicate-session, malformed, expected-hello}`。`ClientHelloVerifier` 只会产生前 5 个;`expected-hello` / `duplicate-session` / `malformed` 是 verifier 之外的 transport-layer 检查产生的。

**Alternatives considered**:
- 自由文本 `reason: String`。**Rejected** — 客户端要稳健提示,必须能做 switch-on 分类;自由文本等于没契约。
- `sealed class HelloErrReason` 在线上。**Rejected** — wire 上还是 JSON,非必要不把 `@Serializable` 的 sealed 引入到 `WireMessage.HelloErr.reason` 字段;字符串 + 固定字母表最朴素。

### D4: `TransportPort` 在 `commonMain`,actual 只先 `jvmMain`

**Decision**:

```kotlin
public interface TransportPort {
    public suspend fun connect(endpoint: TransportEndpoint, identity: DeviceIdentity): Result<TransportSession>
}

public interface TransportSession {
    public val incoming: Flow<WireMessage>
    public suspend fun send(msg: WireMessage)
    public suspend fun close()
}
```

`KtorRelayClient : TransportPort` 实现在 `shared/src/jvmMain`,同时也供 `relay` 模块本地测试用。iOS 的实现留给 W2.1 同 Ed25519 iOS actual 一起落地 — 二者测试 fixture 可共享。

**Alternatives considered**:
- 把 adapter 整个放到 `relay` 模块。**Rejected** — `relay` 是**服务端**,而 `shared` 是端到端客户端 state;后续 Android/iOS 必须能复用这份 adapter。
- 用 `expect`/`actual` 实现 `KtorRelayClient` 本身。**Rejected** — 端口/适配器已经提供抽象边界,多一层 `expect` 没有收益(Ktor 客户端本身是 MPP)。

### D5: relay-cli `pair` / `revoke` / `serve` 共享同一个 JVM 进程 state

**Decision**: 三个子命令都加载同一份 `.ohmymobilecc/relay/state/`(或等价路径)里的 `PubkeyRegistry` 持久层(当前仍是 `InMemoryPubkeyRegistry` + 未来 W2.3 的 SqlDelight 适配器),并在 `ServeCommand` 启动时 bind 给 `RelayServer`。

**Alternatives considered**:
- CLI 与 serve 分为独立进程,通过 IPC 通信。**Rejected** — 不必要的复杂度;relay 本来就是单 JVM 进程。
- CLI 不动 registry,只打印二维码/命令供 serve 读取。**Rejected** — 语义不清,用户会 confused "为什么 revoke 不立即生效"。

## Risks / Trade-offs

- **R1: revoke 不立即踢已握手连接** → Mitigation: 在 pairing spec 显式写出,W1.6 补齐。attacker 已持有私钥的情况下,攻击窗口 = 下一次 WS 心跳/hello 再连接前。
- **R2: `SingleConnectionRegistry` 的 token 取唯一性依赖调用方** → Mitigation: ServeCommand 用 `AtomicLong.incrementAndGet()` 作为 token 来源,单进程内单调递增即足够。
- **R3: `KtorRelayClient` 的 `jvmMain` 实现未被 Android/iOS 共享** → Mitigation: W2.1 提 iOS actual 时 fixtures 能从 `jvmTest` 抄过去;ed25519 也是这个路径。
- **R4: HelloErr alphabet 将来不够用** → Mitigation: 增加一个值需要 spec change,OpenSpec 流程兜底。

## Migration Plan

本 change 是 W1.5 的**最后一步**,不涉及已 archived spec 的行为 breaking:

1. 提 proposal → 审批 → apply(按 tasks.md)。
2. W1.5 merge 到 main 后,**先** archive `add-w15-relay-transport-and-cli`,**再** archive `add-ed25519-platform-crypto-impl`(它的 7.4 task 挂在这次 merge)。
3. W1.6 开新 change `update-pairing-revoke-kick-live-sessions`,把 R1 解除。

## Open Questions

- **OQ1**: `relay-cli serve` 的 `--port` 默认值?取 `48964`?(需要和 W1.6 Android app 商量;W1.5 暂可写死 + 从 env 覆盖)
- **OQ2**: `TransportEndpoint` 里是否要直接接受 Tailscale hostname 或也接受原始 IP?倾向前者(更 opinionated),但需要进一步确认 client UX 流程 —— 本 change 范围内作为 `data class TransportEndpoint(val host: String, val port: Int, val useTls: Boolean)` 扁平结构,不做校验。
