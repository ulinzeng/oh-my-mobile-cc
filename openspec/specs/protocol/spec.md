# protocol Specification

## Purpose
定义 `oh-my-mobile-cc` 移动端 ↔ 桌面 relay 之间的传输协议，包括：
(1) 与 CC `stream-json` 的 NDJSON 事件模型；
(2) 移动端专用 `WireMessage` sealed 子类型体系（`chat.*` / `approval.*` / `terminal.*` / `file.*`）。
本 capability 是所有其他 capability 的基础契约，必须具备向前兼容性
（未知 `type` / `op` 字段 fallback 为 `Unknown` 而不中断连接）。
## Requirements
### Requirement: CC 事件解码
系统 SHALL 将 CC `stream-json` 的 stdout 按 NDJSON（一行一 JSON）解析为类型化 `CCEvent`。
未知 `type` 字段 SHALL 被包装为 `CCEvent.Unknown(raw)` 而非抛异常。

#### Scenario: 已知事件类型
- **WHEN** 一行 JSON 含 `{"type":"system", ...}`
- **THEN** 解析为 `CCEvent.System` 且 `raw` 保留原始 JsonObject

#### Scenario: 未知事件类型
- **WHEN** 一行 JSON 含 `{"type":"not_yet_specified", ...}`
- **THEN** 解析为 `CCEvent.Unknown(raw)`，不抛异常，调用方可继续消费下一行

### Requirement: CC 事件编码
系统 SHALL **不** 通过 NDJSON 写 CC stdin 的方式回写权限决策。
权限决策由 CC `PreToolUse` hook 子进程的 stdout 承载（见 `approval-inbox` spec），
不在 `claude` 主进程 stdin 流中出现。

系统 SHALL 仍支持向 CC stdin 写入 **非权限类** 的 `stream-json` 消息（如用户后续轮次的
`{"type":"user","message":{...}}`），编码规则保持：一行 JSON + `\n` + flush。

#### Scenario: 非权限事件编码
- **WHEN** 用户在 Chat 界面发送后续消息
- **THEN** relay 以单行 JSON + `\n` + flush 写入 CC stdin

#### Scenario: 禁止权限 NDJSON 写入
- **WHEN** 任意 relay 代码路径尝试向 CC stdin 写 `{"type":"permission_response",...}`
- **THEN** 代码审查 SHALL 拒绝该变更（该路径不是合法的 CC stream-json 输入事件类型）

### Requirement: WireMessage 移动端协议
系统 SHALL 定义 sealed `WireMessage` 基类，覆盖 `chat.*`, `approval.*`, `terminal.*`, `file.*` 四组子类型，均用 `kotlinx.serialization` 的 `classDiscriminator = "op"`。

#### Scenario: 未知 op 字段
- **WHEN** 客户端收到的 JSON 含未注册 `op` 值
- **THEN** 解码为 `WireMessage.Unknown(raw)` 并记录 warn 日志，不中断连接

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

### Requirement: Hook 生命周期事件解析
系统 SHALL 将 CC stream-json 中的 `{"type":"system","subtype":"hook_started"|"hook_response"|"hook_progress",...}` 事件解析为类型化 `CCEvent.HookStarted` / `CCEvent.HookResponse` / `CCEvent.HookProgress`。
所有 hook 事件 SHALL 保留完整原始 `raw: JsonObject`，以容忍 CC 未来的字段扩展。

#### Scenario: PreToolUse hook 生命周期对
- **WHEN** CC stdout 先后出现
  `{"type":"system","subtype":"hook_started","hook_name":"PreToolUse:Bash","hook_event":"PreToolUse","hook_id":"H1","uuid":"U1","session_id":"S1"}`
  与对应的 `hook_response` 行（同 `hook_id`）
- **THEN** 解析为 `CCEvent.HookStarted(hookId="H1", hookName="PreToolUse:Bash", hookEvent="PreToolUse", sessionId="S1", raw=...)`
  与 `CCEvent.HookResponse(hookId="H1", output="...", exitCode=0, outcome="success", raw=...)`

#### Scenario: 未知 hook_event 的 fallback
- **WHEN** 一行 JSON 含 `{"type":"system","subtype":"hook_started","hook_event":"FutureEvent",...}`
- **THEN** 解析为 `CCEvent.HookStarted` 且 `hookEvent="FutureEvent"` 原样保留，不抛异常

### Requirement: Tool use 与 PreToolUse hook 的关联
系统 SHALL 能把 assistant 消息中 `content[].type=="tool_use"` 的块按 `tool_use_id` 与
紧随其后的 `PreToolUse` hook 事件关联起来。关联 SHALL 仅基于 `tool_use_id`（CC 保证唯一），**不** 依赖时间窗口或序号启发式。

#### Scenario: Bash tool_use → PreToolUse 关联
- **WHEN** 一行 JSON 含 `{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","id":"tooluse_X","input":{"command":"ls"}}]}}`
- **AND** 紧接的 `PreToolUse` hook 事件 payload 的 `tool_use_id == "tooluse_X"`
- **THEN** relay SHALL 把二者 join 为一条 `ToolInvocation(toolName="Bash", toolUseId="tooluse_X", input={"command":"ls"})` 逻辑记录

#### Scenario: 仅 tool_use 无 hook（hook 未注册）
- **WHEN** 出现 `tool_use` 块但无任何 `PreToolUse` hook 事件
- **THEN** relay SHALL 跳过审批路径；CC 将按 permission mode 默认规则处置（可能 auto-deny），此时 relay 不生成 Inbox 卡片

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

