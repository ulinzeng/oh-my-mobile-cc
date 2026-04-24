# pairing Specification

## Purpose
定义首次绑定移动端与桌面 relay 的安全配对流程：
6 位一次性配对码 + Ed25519 公钥交换 + nonce 防重放。
网络层假设位于同一 Tailscale tailnet 内；**不** 集成任何 Tailscale SDK
（避免平台依赖蔓延），仅通过操作系统级 Tailscale 客户端提供的路由可达性。
## Requirements
### Requirement: 6 位一次性配对码
系统 SHALL 在 relay 启动时生成 6 位数字码，显示于桌面 stdout 与二维码；码在 `5 minutes` 内有效，使用一次后立即失效。

#### Scenario: 首次配对
- **WHEN** 用户在手机 app 输入桌面上显示的 6 位码
- **AND** 码仍在有效期内
- **THEN** 配对成功，relay 签发并记录客户端 Ed25519 公钥

### Requirement: Ed25519 会话签名
配对成功后，移动客户端 SHALL 生成 Ed25519 keypair，公钥交付 relay；后续每次 WS 连接的 ClientHello 必须附带以私钥签名的 `(session_id, timestamp_ms, nonce)`。Relay SHALL 拒绝签名校验失败或 timestamp 偏移超过 `±60s` 的连接。

#### Scenario: 时间偏移超限
- **WHEN** 客户端 ClientHello 的 timestamp 与 relay 系统时间差 90s
- **THEN** relay 关闭连接，状态码 `1008 policy violation`

### Requirement: Replay 防护
系统 SHALL 缓存最近 `10 minutes` 内所有已接受 ClientHello 的 nonce；重复 nonce 的连接尝试 SHALL 被拒绝。

#### Scenario: 重放攻击
- **WHEN** 攻击者捕获合法 ClientHello 并立即重放
- **THEN** relay 在 nonce 缓存命中时拒绝，状态码 `1008`

### Requirement: 取消配对
系统 SHALL 允许桌面端通过 `relay-cli revoke <pubkey-fp>` 命令撤销客户端公钥；撤销后该客户端所有活跃连接 SHALL 立即被踢出。

#### Scenario: 撤销后重连
- **WHEN** 公钥 `fp_abc` 已被撤销
- **AND** 持有该私钥的客户端尝试重连
- **THEN** relay 拒绝并回 `1008`

### Requirement: 不依赖 Tailscale SDK
系统 SHALL **不** 在 app 中集成 Tailscale SDK；网络可达性由用户自行登录 Tailscale 保证。

#### Scenario: 未连 Tailnet
- **WHEN** 手机未登录 Tailscale 或桌面 relay 地址不可达
- **AND** 客户端尝试连接
- **THEN** Ktor Client 超时（5s）并向用户显示 `NetworkUnreachable` 引导界面

### Requirement: HelloErr reason alphabet

系统 SHALL 在 relay 侧产生的 `WireMessage.HelloErr` 的 `reason` 字段取值,仅允许以下 8 个字符串中的一个,客户端 SHALL 可基于此做 switch-on 分类:

- `skew` — 客户端 timestamp 与 relay 系统时间差 > ±60s(见已有 "Ed25519 会话签名" 要求)
- `nonce` — nonce 已在 10 分钟 TTL 内出现过(见已有 "Replay 防护" 要求)
- `sig` — Ed25519 签名校验失败或 `sig` 字段 base64url 解码失败或长度 ≠ 64
- `unpaired` — `deviceId` 未在 `PubkeyRegistry` 注册
- `revoked` — `deviceId` 已被撤销 (见已有 "取消配对" 要求)
- `duplicate-session` — 同 `sessionId` 已有活跃 WS 连接(见 `protocol` spec "传输语义")
- `malformed` — 首帧不是合法 JSON 或反序列化失败
- `expected-hello` — 首帧是合法 WireMessage,但其 `op` 不是 `hello.client`

扩展新的 reason 值 SHALL 通过新的 OpenSpec change 反映到本 requirement。

#### Scenario: 签名校验失败
- **WHEN** 客户端发 ClientHello, sig 字段对应的 Ed25519 签名对 canonical 输入不合法
- **THEN** relay SHALL 回 `HelloErr(reason="sig")` 并以 close code 1008 关闭 WS

#### Scenario: 扩展未经 proposal
- **WHEN** 任意 relay 代码路径尝试构造 `HelloErr(reason = "some_new_value")`
- **THEN** 代码审查 SHALL 拒绝该变更,直到对应 OpenSpec change 审批通过

### Requirement: 撤销不立即踢已握手连接(W1.5 已知债)

系统 SHALL 接受"撤销操作不立即关闭已握手连接"这一受限语义:`PubkeyRegistry.revoke` 执行时,relay MAY 不立即关闭已握手的活跃 WS 连接,已握手连接的终结最迟 SHALL 在下一次该客户端发起 `ClientHello` 时完成(此时 `ClientHelloVerifier` 必须返回 `Err("revoked")`,WS 关 1008)。

本要求明确记录 W1.5 的已知行为边界;W1.6 将通过独立的 OpenSpec change 反悔本条,加入 `PubkeyRegistry.revoke` 触发同 deviceId 活跃连接即时关闭的语义。任何在 W1.5 阶段额外实现 "即时踢出" 的代码路径 SHALL 被代码审查拒绝,避免隐性扩大 W1.5 的行为面。

#### Scenario: revoke 时存在活跃 WS
- **WHEN** 桌面端执行 `relay-cli revoke <deviceId>`,此时该设备已有一条活跃 WS 连接
- **THEN** relay 更新 registry 的 revokedAtMs,但**不**强制关闭存量 WS 连接
- **AND** 下一次该设备发 hello 时 relay 回 `HelloErr(reason="revoked")` 并关 1008

#### Scenario: 撤销后重连(已有 Scenario 的补强)
- **WHEN** `deviceId=D1` 被 `revoke`,设备完全断网,再次上线尝试连 relay
- **THEN** relay 在收到 hello 时回 `HelloErr(reason="revoked")` 并关 1008

### Requirement: relay-cli 运维子命令 pair / revoke

系统 SHALL 提供 `relay-cli pair` 与 `relay-cli revoke <deviceId>` 子命令,复用 W1.4 引入的 `RelayCli` 分发骨架(`approval-bridge` 是第一个,本 change 追加 `pair` / `revoke` 两个)。

- `relay-cli pair`
  - stdout SHALL 打印 **6 位数字配对码**(来源:`PairingService.newCode(now)`,与已有 "6 位一次性配对码" 要求一致)
  - 进程 SHALL 阻塞 **至多 5 分钟**,期间接受移动端通过 pairing-flow(具体 flow 在 W1.6 / W2.1 落地)提交的 Ed25519 pubkey
  - 收到 pubkey 后 SHALL 验证 6 位码 → 用 `PubkeyRegistry.register` 写入 → stdout 打印 `paired deviceId=<dId>` → exit 0
  - 5 分钟超时 SHALL 以 exit code 非 0 退出并打印 `pairing timeout`

- `relay-cli revoke <deviceId>`
  - SHALL 以位置参数接收 `deviceId`
  - SHALL 调用 `PubkeyRegistry.revoke(DeviceId(arg), now)`
  - 未注册的 deviceId SHALL 视为 no-op,退出 0,但 stdout 给出 `warning: deviceId not registered`
  - 已注册 deviceId SHALL 更新 `revokedAtMs`,退出 0,stdout 给出 `revoked deviceId=<dId> at=<ts>`

#### Scenario: 正常 pair 流程
- **WHEN** 桌面执行 `relay-cli pair`
- **THEN** stdout 打印 6 位数字码
- **AND** 进程阻塞等待 pubkey 到达

#### Scenario: pair 超时
- **WHEN** 5 分钟内无任何客户端提交 pubkey
- **THEN** 子进程以非 0 退出,stdout 打印 `pairing timeout`

#### Scenario: 正常 revoke
- **WHEN** 桌面执行 `relay-cli revoke dId_abc`,且 `PubkeyRegistry` 有该条记录
- **THEN** registry 的该条 `revokedAtMs` 被置为 `now`
- **AND** 进程 exit 0,stdout 打印 `revoked deviceId=dId_abc at=<ts>`

#### Scenario: 未注册 deviceId 的 revoke
- **WHEN** 桌面执行 `relay-cli revoke dId_does_not_exist`
- **THEN** 进程 exit 0,stdout 打印 warning,registry 无变化

### Requirement: Ed25519 implementation uses vetted platform crypto

The system SHALL implement Ed25519 keypair generation, signing, and verification using **`expect`/`actual` declarations** backed by platform-native or well-maintained library primitives. Pure-Kotlin reimplementation of Ed25519 field arithmetic, group arithmetic, or SHA-512 is explicitly **prohibited** as a default implementation strategy.

- JVM (`:relay`) actual MUST use BouncyCastle 1.78+ (`org.bouncycastle:bcprov-jdk18on`).
- Android (`androidMain`) actual MUST use BouncyCastle 1.78+; a future migration to `java.security.Signature("Ed25519")` MAY replace BouncyCastle when `minSdk` rises to 34.
- iOS (`iosMain`) actual MAY stub with `NotImplementedError` during W1.5 while the iOS client is not yet wired; when the actual ships, it MUST pass the same RFC 8032 test vectors used by the JVM actual.

Every actual SHALL pass **RFC 8032 §7.1 Ed25519 test vectors** (at minimum Vector 1 and Vector 2) as contract tests. Contract tests SHALL live in `shared/src/commonTest/` and run on every supported target.

#### Scenario: JVM actual passes RFC 8032 Vector 1
- **WHEN** the JVM Ed25519 actual signs the empty message with RFC 8032 Vector 1 seed `9d61b19d...7f60`
- **THEN** the resulting signature byte-for-byte equals the RFC-prescribed signature `e5564300...a100b`

#### Scenario: JVM actual passes RFC 8032 Vector 2
- **WHEN** the JVM Ed25519 actual signs the single-byte message `0x72` with RFC 8032 Vector 2 seed `4ccd089b...a6fb`
- **THEN** the resulting signature byte-for-byte equals the RFC-prescribed signature `92a009a9...0c00`

#### Scenario: iOS stub is explicit
- **WHEN** the iOS actual's `sign` or `verify` is invoked during W1.5
- **THEN** it throws `NotImplementedError` with a message pointing to the follow-up change ID that will deliver the real iOS actual

#### Scenario: Pure-Kotlin reimplementation blocked at review
- **WHEN** a diff proposes adding `FieldElement`, `Scalar`, `EdwardsPoint`, or hand-rolled `Sha512` classes inside `shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/`
- **THEN** code review SHALL reject the change unless it is accompanied by an approved superseding OpenSpec proposal that removes this requirement

