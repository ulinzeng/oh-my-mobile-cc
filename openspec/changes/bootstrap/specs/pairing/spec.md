# pairing — 设备配对（delta）

## ADDED Requirements

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
