## 1. Relay 端 — EventLog
- [x] 1.1 新建 `relay/server/EventLog.kt`：环形缓冲 + AtomicLong seq 分配 + ReadWriteLock
- [x] 1.2 实现 `append(msg, nowMs): SequencedEvent`、`replaySince(afterSeq): List<SequencedEvent>`、`oldestSeq()`、`latestSeq()`
- [x] 1.3 `EventLogTest`：append、replaySince 精确性、环形淘汰、并发读写安全、空 log 边界、`lastEventSeq > latestSeq` 异常 seq

## 2. 协议扩展 — WireMessage
- [x] 2.1 `ClientHello` 新增 `lastEventSeq: Long?`（可选字段，JSON 不出现=null）
- [x] 2.2 `HelloOk` 新增 `oldestSeq: Long`、`latestSeq: Long`（默认 0）
- [x] 2.3 新增 `ReplayEnd(replayedCount: Int, fromSeq: Long, toSeq: Long)` — op=`replay.end`
- [x] 2.4 新增 `SessionEnded(sessionId: String, reason: String)` — op=`session.ended`
- [x] 2.5 `WireMessageSerializer` encode/decode 支持新字段和新子类型
- [x] 2.6 所有 outbound 事件追加 `seq` 字段到 JSON 编码
- [x] 2.7 `ClientHelloLastEventSeqTest`、`ReplayEndTest`、`SessionEndedTest`、`HelloOkSeqFieldsTest` 编解码测试

## 3. Relay 集成 — RelayServer 回放
- [x] 3.1 `RelayServerConfig` 新增 `eventLog: EventLog` + `sequencedOutbound: SharedFlow<SequencedEvent>`
- [x] 3.2 outbound 衔接层：`outbound.collect → eventLog.append → sequencedOutbound.emit`
- [x] 3.3 `RelayServer.handle` 改造：握手后先订阅再回放再 drain（D4 方案）
- [x] 3.4 `HelloOk` 响应填充 `oldestSeq` / `latestSeq`
- [x] 3.5 CC 进程退出时 emit `SessionEnded` + 关闭 WS
- [x] 3.6 `RelayServerReplayTest`：重连回放正确性、首次连接不回放、回放期间新事件不丢不重、seq gap 场景
- [x] 3.7 `SessionEndedTest`：CC 退出推送 + WS 关闭

## 4. 客户端 — ReconnectingTransportPort
- [x] 4.1 新建 `shared/core/transport/ReconnectingTransportPort.kt`：包装 TransportPort + 指数退避 + 状态机
- [x] 4.2 `ConnectionState` sealed class（Disconnected / Connecting / Replaying / Connected / Reconnecting）
- [x] 4.3 `IncomingEvent` sealed class（Message / ReplayComplete / ConnectionEstablished / ConnectionLost）
- [x] 4.4 `ReconnectConfig` 数据类（initialDelayMs / maxDelayMs / multiplier / jitterFraction / maxRetries）
- [x] 4.5 内部 `lastSeenSeq: AtomicLong` 追踪 + 构造时接受 `initialLastSeq: Long?`
- [x] 4.6 FATAL reason 集合（revoked / unpaired / sig）不重连
- [x] 4.7 `ReconnectingTransportPortTest`：断线自动重连、退避时间正确、FATAL 停止、seq 追踪
- [x] 4.8 `ConnectionStateMachineTest`：状态流转完整覆盖
- [x] 4.9 `ReplayIntegrationTest`：模拟 relay 回放 + ReplayEnd → events flow 输出正确
- [x] 4.10 `SeqGapDetectionTest`：`lastEventSeq < oldestSeq` 时 `hasGap = true`
