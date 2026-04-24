## MODIFIED Requirements

### Requirement: WireMessage 移动端协议
系统 SHALL 定义 sealed `WireMessage` 基类，覆盖 `chat.*`, `approval.*`, `terminal.*`, `file.*`, `hello.*`, `replay.*`, `session.*` 子类型组，均用 `kotlinx.serialization` 的 `classDiscriminator = "op"`。

所有从 relay 推送给客户端的 WireMessage 帧 SHALL 在 JSON 编码中包含 `seq: Long` 字段，表示该事件在当前 CC session 的 EventLog 中的单调递增序号。客户端 SHALL 持久化最后收到的 `seq` 值以支持断线重连回放。

#### Scenario: 未知 op 字段
- **WHEN** 客户端收到的 JSON 含未注册 `op` 值
- **THEN** 解码为 `WireMessage.Unknown(raw)` 并记录 warn 日志，不中断连接

#### Scenario: outbound 帧带 seq
- **WHEN** relay 向客户端 WS 推送任何 WireMessage 帧
- **THEN** JSON 编码中 SHALL 包含 `seq` 字段，值为 EventLog 分配的单调递增序号

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

### Requirement: EventLog 内存事件缓冲
relay SHALL 维护一个内存环形缓冲 `EventLog`，为每条 outbound `WireMessage` 分配单调递增的 `seq: Long`（从 1 开始）。`EventLog` 的容量 SHALL 默认为 10,000 条；超出容量时最老的事件 SHALL 被覆盖。

`EventLog` 的生命周期 SHALL 绑定到当前 CC session — CC 进程退出后 EventLog 被释放，不持久化到磁盘。

`EventLog` SHALL 提供线程安全的 `append(msg, nowMs): SequencedEvent` 和 `replaySince(afterSeq): List<SequencedEvent>` 方法。`replaySince(afterSeq)` SHALL 返回 `seq > afterSeq` 的所有事件；当 `afterSeq < oldestSeq` 时 SHALL 返回当前缓冲区全部内容（尽力回放）。

#### Scenario: 正常追加与回放
- **WHEN** EventLog 已 append 了 seq 1~100 的事件
- **AND** 调用 `replaySince(42)`
- **THEN** 返回 seq 43~100 共 58 条事件，按 seq 升序排列

#### Scenario: 环形淘汰
- **WHEN** EventLog 容量为 100，已 append 了 seq 1~150 的事件
- **THEN** `oldestSeq()` 返回 51，`replaySince(0)` 返回 seq 51~150

#### Scenario: afterSeq 在缓冲区之前
- **WHEN** EventLog 的 `oldestSeq()` 为 51
- **AND** 调用 `replaySince(20)`
- **THEN** 返回当前缓冲区全部内容（seq 51~最新），调用方可根据 oldestSeq 判断存在缺口

### Requirement: 断线重连回放
relay SHALL 在握手成功后、进入实时推送模式前，根据 `ClientHello.lastEventSeq` 进行精确回放。

回放流程 SHALL 为：
1. 先订阅 `sequencedOutbound` SharedFlow（缓存实时事件到 Channel）
2. 调用 `EventLog.replaySince(lastEventSeq)` 获取历史事件并逐条推送
3. 发送 `WireMessage.ReplayEnd` 帧标记回放结束
4. drain Channel 中的实时事件，按 seq 去重（跳过已回放的 seq）

当 `ClientHello.lastEventSeq` 为 null 时 SHALL 跳过回放阶段。

#### Scenario: 重连回放
- **WHEN** 客户端断线后重连，ClientHello 带 `lastEventSeq=42`
- **AND** EventLog 当前包含 seq 1~100
- **THEN** relay 先推送 seq 43~100 的事件，再发 `ReplayEnd(replayedCount=58, fromSeq=42, toSeq=100)`，然后切换到实时推送

#### Scenario: 首次连接不回放
- **WHEN** 客户端首次连接，ClientHello 的 `lastEventSeq` 为 null
- **THEN** relay 不回放，直接进入实时推送模式（HelloOk 仍带 oldestSeq/latestSeq）

#### Scenario: 回放期间新事件不丢不重
- **WHEN** 回放 seq 43~100 期间，新事件 seq 101~105 被 append 到 EventLog
- **THEN** 客户端 SHALL 收到 seq 43~105 的完整序列，无丢失无重复

### Requirement: ReplayEnd 帧
relay SHALL 在回放结束后发送 `WireMessage.ReplayEnd` 帧，op 为 `replay.end`，包含 `replayedCount: Int`（回放条数）、`fromSeq: Long`（回放起始 seq，exclusive）、`toSeq: Long`（回放结束 seq，inclusive）。

当无事件需要回放时（`lastEventSeq >= latestSeq`），relay SHALL 发送 `ReplayEnd(replayedCount=0, fromSeq=N, toSeq=N)`。

#### Scenario: 回放结束标记
- **WHEN** relay 完成回放 seq 43~100
- **THEN** 发送 `{"op":"replay.end","replayedCount":58,"fromSeq":42,"toSeq":100}`

#### Scenario: 零回放
- **WHEN** 客户端 `lastEventSeq=100` 且 `latestSeq=100`
- **THEN** 发送 `ReplayEnd(replayedCount=0, fromSeq=100, toSeq=100)` 后进入实时模式

### Requirement: SessionEnded 帧
relay SHALL 在 CC 进程退出后通过 WS 推送 `WireMessage.SessionEnded` 帧，op 为 `session.ended`，包含 `sessionId: String` 和 `reason: String`（如 `"process_exited"`、`"user_stopped"`）。

客户端收到 `SessionEnded` 后 SHALL 停止重连循环并标记该 session 为已结束。

#### Scenario: CC 进程正常退出
- **WHEN** CC 进程 exit code 0
- **THEN** relay 推送 `{"op":"session.ended","sessionId":"s1","reason":"process_exited"}` 并关闭 WS

#### Scenario: 客户端收到 SessionEnded
- **WHEN** 客户端收到 `SessionEnded`
- **THEN** 停止自动重连，`connectionState` 转为 `Disconnected`

### Requirement: 共享 ReconnectingTransportPort 契约

`shared` 模块 SHALL 暴露 `ReconnectingTransportPort` 封装层，对上层提供稳定的 `events: Flow<IncomingEvent>` 和 `connectionState: StateFlow<ConnectionState>`，内部管理断线检测、自动重连、seq 追踪。

`ReconnectingTransportPort` SHALL 使用指数退避策略重连：初始延迟 1s，倍数 2x，上限 30s，±20% 随机 jitter。当 relay 返回 `HelloErr` 且 reason 为 `revoked`、`unpaired` 或 `sig` 时 SHALL 停止重连（FATAL）。

`ConnectionState` SHALL 为 sealed class，包含 `Disconnected`、`Connecting`、`Replaying(progress)`、`Connected(serverTimeMs)`、`Reconnecting(attempt, nextRetryMs)` 五个子类型。

`IncomingEvent` SHALL 为 sealed class，包含 `Message(seq, message)`、`ReplayComplete(count, hasGap)`、`ConnectionEstablished`、`ConnectionLost(reason)` 四个子类型。

`ReconnectingTransportPort` 构造时 SHALL 接受 `initialLastSeq: Long?` 参数（由平台层从持久化存储加载），内部维护 `lastSeenSeq` 随每条收到的事件更新。seq 持久化到磁盘的职责由上层平台代码（android/ios）承担，`shared` 模块不依赖平台特定存储 API。

#### Scenario: 断线自动重连
- **WHEN** WS 连接因网络异常断开
- **THEN** `connectionState` 先转为 `Reconnecting(attempt=0, nextRetryMs≈1000)`，等待后尝试重连

#### Scenario: 指数退避
- **WHEN** 连续重连失败
- **THEN** 等待时间按 1s → 2s → 4s → 8s → 16s → 30s（cap）递增，每次加 ±20% jitter

#### Scenario: FATAL reason 停止重连
- **WHEN** relay 返回 `HelloErr(reason="revoked")`
- **THEN** `connectionState` 转为 `Disconnected`，不再重试

#### Scenario: seq gap 检测
- **WHEN** 重连后 HelloOk 的 `oldestSeq=51` 但客户端 `lastEventSeq=20`
- **THEN** `IncomingEvent.ReplayComplete` 的 `hasGap=true`
