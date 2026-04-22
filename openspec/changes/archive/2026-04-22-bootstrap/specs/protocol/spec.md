# protocol — 传输协议（delta）

## ADDED Requirements

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
系统 SHALL 能将 `CCEvent.PermissionResponse` 以 NDJSON 编码（一行 JSON + `\n` + flush）写入 CC stdin。

#### Scenario: 审批回写
- **WHEN** relay 收到 `ApprovalResponded(allow)` WireMessage
- **THEN** 向 CC stdin 写入单行 JSON `{"type":"permission_response","request_id":"...","decision":"allow"}` 并 flush

### Requirement: WireMessage 移动端协议
系统 SHALL 定义 sealed `WireMessage` 基类，覆盖 `chat.*`, `approval.*`, `terminal.*`, `file.*` 四组子类型，均用 `kotlinx.serialization` 的 `classDiscriminator = "op"`。

#### Scenario: 未知 op 字段
- **WHEN** 客户端收到的 JSON 含未注册 `op` 值
- **THEN** 解码为 `WireMessage.Unknown(raw)` 并记录 warn 日志，不中断连接

### Requirement: 传输语义
系统 SHALL 以单一活跃 WebSocket 承载一条 session 的所有消息。
任意一方 SHALL 在收到格式错误或签名校验失败的消息时关闭连接，状态码 `1007`。

#### Scenario: 单连接约束
- **WHEN** 第二个 WebSocket 尝试使用同一 session_id 连入 relay
- **THEN** relay 以状态码 `1013`（try again later）拒绝新连接
