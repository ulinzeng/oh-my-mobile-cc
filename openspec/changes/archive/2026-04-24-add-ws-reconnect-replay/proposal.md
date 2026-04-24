# Change: Add WebSocket reconnect with offline event replay

## Why
移动端进程随时可能被 OS 杀死或网络闪断。当前 relay 的 outbound 是 fire-and-forget 的
`SharedFlow`，断线期间所有事件丢失。用户重新打开 app 后看到的是空白，必须等待下一条
实时事件才能恢复上下文。这使得 Approval Inbox 等时间敏感功能在实际移动场景下不可用。

## What Changes
- 在 relay 端引入内存 `EventLog`（环形缓冲），为每条 outbound 事件分配单调递增 `seq`
- 扩展 `WireMessage.ClientHello` 新增可选 `lastEventSeq` 字段（不参与 Ed25519 签名）
- 扩展 `WireMessage.HelloOk` 新增 `oldestSeq` / `latestSeq` 字段
- 新增 `WireMessage.ReplayEnd` 帧标记回放结束
- 新增 `WireMessage.SessionEnded` 帧通知客户端 CC 进程退出
- relay 握手成功后，根据 `lastEventSeq` 精确回放离线期间事件，再切换到实时推送
- 在 `shared` 模块新增 `ReconnectingTransportPort` 封装指数退避重连 + seq 追踪 + 状态机
- EventLog 生命周期绑定 CC session（内存，不持久化磁盘）

## Impact
- Affected specs: `protocol`（WireMessage 扩展 + 回放语义）、`pairing`（ClientHello 字段）
- Affected code:
  - `relay/server/` — EventLog、RelayServer 回放集成、RelayServerConfig 扩展
  - `shared/core/protocol/` — WireMessage 新子类型 + 序列化
  - `shared/core/transport/` — ReconnectingTransportPort、ConnectionState、IncomingEvent
  - `relay/cli/ServeCommand` — 衔接 EventLog 到 outbound 管道
