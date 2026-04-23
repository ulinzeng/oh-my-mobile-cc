## ADDED Requirements

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
