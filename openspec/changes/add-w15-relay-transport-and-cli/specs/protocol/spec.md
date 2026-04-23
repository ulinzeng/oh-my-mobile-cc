## MODIFIED Requirements

### Requirement: 传输语义
系统 SHALL 以单一活跃 WebSocket 承载一条 session 的所有消息。
任意一方 SHALL 在收到格式错误或签名校验失败的消息时关闭连接,状态码 `1007`。

连接首帧 SHALL 为 `WireMessage.ClientHello`;relay 在首帧为合法 WireMessage 但 `op != hello.client` 时 SHALL 回 `WireMessage.HelloErr(reason="expected-hello")` 并关 1008;首帧非合法 JSON 或反序列化失败时 SHALL 回 `HelloErr(reason="malformed")` 并关 1007。

`HelloErr.reason` 到 close code 的映射 SHALL 如下:
| reason | close code |
|---|---|
| `skew` | 1008 |
| `nonce` | 1008 |
| `sig` | 1008 |
| `unpaired` | 1008 |
| `revoked` | 1008 |
| `expected-hello` | 1008 |
| `duplicate-session` | 1013 |
| `malformed` | 1007 |

扩展新的 reason / close code 映射 SHALL 通过新的 OpenSpec change 反映到本表。

#### Scenario: 单连接约束
- **WHEN** 第二个 WebSocket 尝试使用同一 session_id 连入 relay
- **THEN** relay 以状态码 `1013`(try again later)拒绝新连接,并附 `HelloErr(reason="duplicate-session")` 帧

#### Scenario: 首帧错 op
- **WHEN** 客户端 WS 握手成功,首帧发 `WireMessage.ChatMessage`(而非 `ClientHello`)
- **THEN** relay 回 `HelloErr(reason="expected-hello")` 并关 1008

#### Scenario: 首帧非合法 JSON
- **WHEN** 客户端 WS 握手成功,首帧发 Frame.Text 内容为 `"not a json at all"`
- **THEN** relay 回 `HelloErr(reason="malformed")` 并关 1007

## ADDED Requirements

### Requirement: 共享 TransportPort 契约

`shared` 模块 SHALL 暴露 `TransportPort` 端口以及 `TransportSession` 会话抽象:

```kotlin
public interface TransportPort {
    public suspend fun connect(
        endpoint: TransportEndpoint,
        identity: DeviceIdentity,
    ): Result<TransportSession>
}

public interface TransportSession {
    public val incoming: kotlinx.coroutines.flow.Flow<WireMessage>
    public suspend fun send(msg: WireMessage)
    public suspend fun close()
}
```

`TransportPort.connect` 的契约 SHALL 为:
1. 打开到 `endpoint` 的 WebSocket;
2. 用 `identity` 的 Ed25519 私钥对 canonical `sessionId|timestampMs|nonce` 签名,发送 `WireMessage.ClientHello` 作为**首帧**;
3. 等待 relay 回 `HelloOk` → 返回 `Ok(TransportSession)`;等待 relay 回 `HelloErr` → 返回 `Result.failure(RelayError.Rejected(reason))`;连接期间收到任何其他 WireMessage SHALL 返回 `Result.failure(RelayError.ProtocolViolation)`。
4. W1.5 SHALL 落地 `jvmMain` actual(`KtorRelayClient`);`androidMain` / `iosMain` actual 不在本 change 范围,将在后续 W2.x change 中补齐,届时必须满足同一契约。

#### Scenario: 握手成功
- **WHEN** 客户端 `TransportPort.connect(ep, identity)` 与 relay 成功握手,relay 回 HelloOk
- **THEN** 调用返回 `Result.success(TransportSession)`, `incoming` flow 可消费后续 relay → client 帧

#### Scenario: relay 拒 revoked
- **WHEN** 客户端握手,relay 回 `HelloErr(reason="revoked")`
- **THEN** 调用返回 `Result.failure(RelayError.Rejected(reason = "revoked"))`,不暴露 `TransportSession`

#### Scenario: relay 在 HelloOk 前发协议不合法的帧
- **WHEN** relay 在回 `HelloOk` 之前发了一条 `WireMessage.ApprovalRequested`
- **THEN** 调用返回 `Result.failure(RelayError.ProtocolViolation)`

### Requirement: relay-cli serve 子命令

系统 SHALL 提供 `relay-cli serve` 子命令,以启动监听 WS 的 relay 服务:

- SHALL 从 `--port <n>` 参数或 `RELAY_PORT` 环境变量读取端口(二者都缺省时使用默认值 `48964`)
- SHALL 用**同一 JVM 进程内共享**的 `PubkeyRegistry` / `NonceCache` 实例 —— 确保 `relay-cli pair` / `revoke` 的写入对 `serve` 立即可见
- SHALL 启动 Ktor Netty engine,安装 `RelayServer` 插件,挂载 `/ws` 端点
- SHALL 绑定 `ApprovalBridge.outbound`(W1.4)到 WS 出向流,并把 WS 入向的 `WireMessage.ApprovalResponded` 回流给 `ApprovalBridge.submitDecision`(`onInbound` 注入点)
- SHALL 在收到 SIGINT / SIGTERM 时优雅关闭(`stop(gracePeriodMillis, timeoutMillis)` 用默认值)

#### Scenario: 默认端口启动
- **WHEN** 桌面执行 `relay-cli serve`(无 `--port`,无 `RELAY_PORT`)
- **THEN** 进程监听 48964,stdout 打印 `relay listening on :48964`

#### Scenario: --port 覆盖
- **WHEN** 桌面执行 `relay-cli serve --port 10001`
- **THEN** 进程监听 10001

#### Scenario: SIGINT 优雅关闭
- **WHEN** 桌面向进程发 SIGINT
- **THEN** 进程关闭 `/ws` 并 exit 0
