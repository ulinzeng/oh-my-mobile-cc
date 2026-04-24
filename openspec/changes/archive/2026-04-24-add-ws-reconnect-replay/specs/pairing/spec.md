## MODIFIED Requirements

### Requirement: Ed25519 会话签名
配对成功后，移动客户端 SHALL 生成 Ed25519 keypair，公钥交付 relay；后续每次 WS 连接的 ClientHello 必须附带以私钥签名的 `(session_id, timestamp_ms, nonce)`。Relay SHALL 拒绝签名校验失败或 timestamp 偏移超过 `±60s` 的连接。

`ClientHello` SHALL 包含可选字段 `lastEventSeq: Long?`，表示客户端上次收到的最后一个事件的 seq。该字段 SHALL **不参与** Ed25519 签名的 canonical input（签名输入保持 `sessionId|timestampMs|nonce` 不变）。`lastEventSeq` 为 null 时 JSON 编码中 SHALL 不出现该字段（向后兼容）。

#### Scenario: 时间偏移超限
- **WHEN** 客户端 ClientHello 的 timestamp 与 relay 系统时间差 90s
- **THEN** relay 关闭连接，状态码 `1008 policy violation`

#### Scenario: 重连带 lastEventSeq
- **WHEN** 客户端发送 `ClientHello(lastEventSeq=42, ...)`，签名仅覆盖 `sessionId|timestampMs|nonce`
- **THEN** relay 正常验签通过（lastEventSeq 不影响签名校验），握手成功后根据 lastEventSeq 回放事件

#### Scenario: 首次连接不带 lastEventSeq
- **WHEN** 客户端首次连接，ClientHello 不包含 `lastEventSeq` 字段
- **THEN** relay 解码 `lastEventSeq` 为 null，握手正常通过，不触发回放

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

`HelloOk` SHALL 包含 `oldestSeq: Long`（EventLog 中最老事件的 seq，0=无事件）和 `latestSeq: Long`（最新事件的 seq，0=无事件），使客户端可判断回放是否存在缺口（`lastEventSeq < oldestSeq`）。

#### Scenario: 签名校验失败
- **WHEN** 客户端发 ClientHello, sig 字段对应的 Ed25519 签名对 canonical 输入不合法
- **THEN** relay SHALL 回 `HelloErr(reason="sig")` 并以 close code 1008 关闭 WS

#### Scenario: 扩展未经 proposal
- **WHEN** 任意 relay 代码路径尝试构造 `HelloErr(reason = "some_new_value")`
- **THEN** 代码审查 SHALL 拒绝该变更,直到对应 OpenSpec change 审批通过

#### Scenario: HelloOk 带 seq 范围
- **WHEN** relay 握手成功，EventLog 包含 seq 1~100
- **THEN** HelloOk 响应 SHALL 包含 `oldestSeq=1, latestSeq=100`
