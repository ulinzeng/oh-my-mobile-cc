## Context
移动端 WS 连接天然不稳定（进程被杀、网络切换、锁屏省电）。relay 需要在 session 生命周期
内缓存事件，使客户端重连后能精确回放离线期间遗漏的消息。

## Goals
- 零事件丢失：断线→重连期间的所有 outbound 事件可被精确回放
- 零重复：回放与实时推送通过 seq 去重，客户端不会收到重复事件
- 向后兼容：老客户端（不发 `lastEventSeq`）行为不变
- 内存可控：环形缓冲有上限，不会因长 session 导致 OOM

## Non-Goals
- 磁盘持久化 EventLog（session 级内存足够）
- 多设备 outbox（当前单设备架构）
- 踢旧连接升级（duplicate-session 语义不变）
- 事件压缩 / 批量传输（V1 逐条回放）
- 客户端 seq 持久化的平台实现（shared 只定义接口，android/ios 各自实现）

## Decisions

### D1: EventLog 环形缓冲（vs SharedFlow replay / per-device outbox）
- **选择**：自定义 `EventLog` 环形缓冲 + seq 索引
- **替代 A**：SharedFlow 大 replay — 不支持从任意 offset 开始回放，浪费带宽
- **替代 B**：per-device outbox — 当前单设备架构下 over-engineering
- **理由**：精确回放、零浪费带宽、O(log n) 查找、内存可控

### D2: ClientHello 带 lastEventSeq（vs 单独 ReplayRequest 消息）
- **选择**：ClientHello 新增可选字段 `lastEventSeq: Long?`
- **替代**：握手后发单独 `ReplayRequest(fromSeq)` 消息 — 多一轮往返
- **理由**：最小协议变更，复用现有握手流程

### D3: lastEventSeq 不参与签名
- `lastEventSeq` 是回放提示，不是安全断言
- 攻击者篡改最坏效果是多收或少收事件，不构成安全问题
- 签名 canonical input 保持 `sessionId|timestampMs|nonce` 不变

### D4: 先订阅后回放再 drain
- relay 在回放前先订阅 `sequencedOutbound` SharedFlow 到 Channel
- 回放历史事件
- drain Channel 时按 seq 去重（跳过已回放的）
- **保证**：不漏（先订阅再回放）、不重（seq 去重）

### D5: 客户端指数退避重连
- 参数：初始 1s、倍数 2x、上限 30s、±20% jitter
- FATAL reason（revoked / unpaired / sig）不重连
- WS 正常断开后重置退避计数

### D6: SessionEnded 帧
- CC 进程退出后 relay 推 `SessionEnded` 通知客户端停止重连
- 客户端收到后标记 session 结束，不再重试

## Risks / Trade-offs

| 风险 | 缓解 |
|---|---|
| EventLog 环形缓冲满了，早期事件被覆盖 | HelloOk 带 oldestSeq/latestSeq，客户端检测 gap |
| 回放期间新事件到达 | D4 方案：先订阅后回放再 drain，seq 去重 |
| Channel.UNLIMITED 回放期间内存 | 回放通常 <1s，Channel 积压极少；且 EventLog cap 本身限制了最大回放量 |
| ReconnectingTransportPort 复杂度 | 状态机明确 5 个状态，每个转换有测试覆盖 |

## Open Questions
- 无（需求已在 brainstorming 阶段充分澄清）
