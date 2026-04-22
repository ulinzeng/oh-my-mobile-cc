---
name: stream-json-protocol
description: 说明 relay 如何通过 `claude -p` 的 stream-json I/O 驱动本机 CC
triggers: ["stream-json", "CCEvent", "permission_request", "WireMessage", "ApprovalBridge", "claude -p", "NDJSON"]
related-specs: [openspec/specs/protocol/spec.md, openspec/specs/approval-inbox/spec.md]
---

# Stream-JSON 协议与 ApprovalBridge

本 skill 说明 **relay 如何用 `claude -p` 的 stream-json I/O 驱动本机 Claude Code CLI**，
并把权限请求桥接给移动端 Inbox。任何涉及 `CCEvent`、`WireMessage`、NDJSON 编解码、
ApprovalBridge 的任务都应先加载本 skill。

本项目不使用 `claude code serve`（在 plan v1→v2 的 "1.txt 修正" 中已明确剔除）。
唯一入口是：

```bash
claude -p --output-format stream-json --input-format stream-json --permission-mode default
```

> **⚠️ 重要声明**：
> CC 的 `permission_request` / `permission_response` 事件 **schema 未公开文档化**。
> 本项目所有与 permission 相关的字段名、嵌套结构**必须**以 W0.6 采集的真实 NDJSON
> fixture 为准，而不是以本文件的示例为准。见第 4 节。

---

## 1. `claude -p` 的运行契约

启动命令：

```bash
claude --bare -p \
    --output-format stream-json \
    --input-format  stream-json \
    --permission-mode default \
    --include-partial-messages
```

行为约定：

- **stdout**：每行一个 JSON 对象（NDJSON），事件流；CC 会持续输出直到进程退出。
- **stdin** ：每行一个 JSON 对象；relay 写完一条必须 `\n` + `flush()`，否则 CC 不消费。
- **stderr**：**独立通道**，承载日志、进度、警告；**绝对不要**把 stderr 当事件流 parse。
- `--permission-mode default` 触发原生权限询问；没有这个 flag 就不会产生 `permission_request`。
- `--include-partial-messages` 会让 assistant 消息以增量片段方式到达（见 GOTCHA 3）。

退出语义：

- CC 正常完成任务后以 `type: "result"` 事件作为最后一条输出，随后进程 exit 0。
- 对端异常（stdin 断开）→ CC 以非零 exit 结束；relay 需 reap 子进程并广播 `RemoteError`。

---

## 2. `CCEvent` 封装模型（待落地）

本项目的 `shared/core/protocol/CCEvent.kt` 计划采用 sealed 层级并预留
**forward-compat 兜底**，避免 CC 新增事件类型时整个 parser 崩掉：

```kotlin
package io.ohmymobilecc.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Top-level event emitted by `claude -p --output-format stream-json`.
 * The set of known subtypes is intentionally OPEN: any unrecognised `type`
 * value falls through to [Unknown] so the stream is never lost.
 */
@Serializable
sealed interface CCEvent {
    val type: String

    @Serializable
    @SerialName("system")
    data class System(
        override val type: String = "system",
        val subtype: String? = null,
        val data: JsonObject = JsonObject(emptyMap()),
    ) : CCEvent

    @Serializable
    @SerialName("user")
    data class User(
        override val type: String = "user",
        val message: JsonObject,
    ) : CCEvent

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        override val type: String = "assistant",
        val message: JsonObject,
        val partial: Boolean = false,
    ) : CCEvent

    @Serializable
    @SerialName("result")
    data class Result(
        override val type: String = "result",
        val subtype: String? = null,
        val usage: JsonObject? = null,
    ) : CCEvent

    /**
     * SCHEMA IS NOT PUBLICLY DOCUMENTED. Fields below are placeholders —
     * the canonical shape is whatever lives in the W0.6 fixture.
     * DO NOT hand-tune; always regenerate from the fixture.
     */
    @Serializable
    @SerialName("permission_request")
    data class PermissionRequest(
        override val type: String = "permission_request",
        val raw: JsonObject,
    ) : CCEvent

    @Serializable
    @SerialName("permission_response")
    data class PermissionResponse(
        override val type: String = "permission_response",
        val raw: JsonObject,
    ) : CCEvent

    /** Forward-compat catch-all for unknown `type` values. */
    @Serializable
    data class Unknown(
        override val type: String,
        val raw: JsonObject,
    ) : CCEvent
}
```

关键点：

- `Unknown` 是 **forward-compat 的生命线**；任何新 `type` 都会命中它而不是抛异常。
- `PermissionRequest` / `PermissionResponse` 仅保留 `raw: JsonObject`，待 W0.6 fixture 到位后再建强类型。
- `Assistant.partial` 对应 `--include-partial-messages`，详见 GOTCHA 3。

---

## 3. NDJSON 分帧规则

NDJSON = "一行一个 JSON"，但在 IPC 场景有几个容易踩的细节。

### 3.1 编码

- 每条事件 = `Json.encodeToString(ev) + "\n"`。
- 换行符必须是 `\n`，不要用 `System.lineSeparator()`（Windows 下会是 `\r\n`，CC 不接受）。
- 写 stdin 后必须立刻 `flush()`，否则 JVM `BufferedWriter` 可能攒着不发。

Kotlin 侧参考实现：

```kotlin
package io.ohmymobilecc.relay.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.Writer

class StdinWriter(
    private val out: Writer,
    private val json: Json,
) {
    @Synchronized
    fun send(line: JsonObject) {
        out.write(json.encodeToString(JsonObject.serializer(), line))
        out.write("\n")
        out.flush()
    }
}
```

`@Synchronized` 不是装饰；见 GOTCHA 1（单写者约束）。

### 3.2 解码

- 用行分隔器读 stdout，逐行 `Json { ignoreUnknownKeys = true }.decodeFromString<CCEvent>(line)`。
- 空行（`""`）必须 skip，不要让它走进 parser。
- 任何解码异常**不应**终止流；应记录一条 `Unknown(type="_parse_error", raw={...})` 继续读。

```kotlin
package io.ohmymobilecc.relay.cli

import io.ohmymobilecc.core.protocol.CCEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader

fun BufferedReader.toCCEventFlow(json: Json): Flow<CCEvent> = flow {
    lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        val ev = runCatching {
            json.decodeFromString(CCEvent.serializer(), line)
        }.getOrElse {
            val wrapped = json.parseToJsonElement(
                """{"line":${json.encodeToString(String.serializer(), line)}}""",
            ).jsonObject
            CCEvent.Unknown(type = "_parse_error", raw = wrapped)
        }
        emit(ev)
    }
}
```

### 3.3 Stderr 与进度

- `stderr` 作为独立 `BufferedReader` 消费，只写 kermit 日志，不影响事件流。
- CC 的非事件 banner（版本信息、hint）可能出现在 stderr 首行，不得解析为 JSON。

---

## 4. Permission 事件 schema：**未公开，以 fixture 为准**

CC 官方文档截至本 skill 写作日**未**公开 `permission_request` / `permission_response`
的字段结构。本项目采取 **fixture-first** 策略：

```
shared/src/commonTest/resources/fixtures/permission_bash_request.ndjson
```

采集命令（plan v2 第 298–307 行）：

```bash
mkdir -p shared/src/commonTest/resources/fixtures
claude --bare -p \
    --output-format stream-json \
    --input-format  stream-json \
    --permission-mode default \
    --include-partial-messages \
    -p "use Bash tool to run: ls /tmp" \
    > shared/src/commonTest/resources/fixtures/permission_bash_request.ndjson
```

### 4.1 门禁（plan v2 W0.6 → W1.4）

- **W0.6** 是 **W1.4 ApprovalBridge 的强前置**。W0.6 未完成之前不得落地任何 permission
  强类型字段；相关代码必须保留 `raw: JsonObject` 占位。
- fixture 至少包含 **一条** `permission_request` 事件，否则 W0.6 视为未完成。
- fixture 变更（重新采集、CC 版本升级）应伴随一次 proposal（扩展 `protocol` capability）。

### 4.2 不要做的事

- ❌ 根据 SDK 文档、博客、本 skill 的示例字段名"推断" schema。
- ❌ 在测试里硬编码不来自 fixture 的 `permission_request` JSON。
- ❌ 把 `PermissionRequest.raw` 提前替换成强类型 `data class` —— 必须等 fixture 落地后再
  跑一次 proposal 并合入。

---

## 5. ApprovalBridge 数据流

ApprovalBridge 把 CC 原生权限询问**转译**为 `WireMessage.Approval*`，经 WSS 送达手机 Inbox。

```
┌────────────────────┐
│  claude -p stdout  │  NDJSON
│  (CCEvent stream)  │
└────────┬───────────┘
         │ parse (CCEvent.PermissionRequest)
         ▼
┌────────────────────────────────────────┐
│ relay/approval/ApprovalBridge          │
│  1. allocate approvalId (uuid)         │
│  2. persist → SQLite `approvals`       │
│  3. start 10-min timeout timer         │
│  4. emit WireMessage.ApprovalRequested │
└────────┬───────────────────────────────┘
         │ WSS
         ▼
┌────────────────────────────────────────┐
│ shared/features/approval/              │
│   ApprovalInteractor                   │
│  StateFlow<List<ApprovalRequest>>      │
└────────┬───────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────┐
│ ui/inbox/InboxScreen                   │
│  Allow / Deny / Customize              │
└────────┬───────────────────────────────┘
         │ WSS (WireMessage.ApprovalResponded)
         ▼
┌────────────────────────────────────────┐
│ relay/approval/ApprovalBridge          │
│  5. match approvalId                   │
│  6. cancel timeout                     │
│  7. write stdin:                       │
│     {"type":"permission_response",...} │
└────────┬───────────────────────────────┘
         ▼
┌────────────────────┐
│  claude -p stdin   │
└────────────────────┘
```

### 5.1 超时 → auto-DENY

- 10 分钟 Inbox 未响应 → ApprovalBridge 自己写一条 `permission_response(decision=deny)`
  给 CC stdin，同时向手机广播：

  ```kotlin
  @Serializable
  @SerialName("approval.expired")
  data class ApprovalExpired(
      val approvalId: String,
      val reason: String, // e.g. "inbox timeout 10m"
  ) : WireMessage
  ```

- `approval.expired` 是 plan v2 明文规定的 wire 事件名（见 `approval-inbox` capability）。
- 超时时间由配置可调，但 spec 默认值是 **600 秒**。

### 5.2 `WireMessage.Approval*`（plan v2 引用）

```kotlin
@Serializable
@SerialName("approval.requested")
data class ApprovalRequested(
    val approvalId: String,
    val sessionId: String,
    val tool: String,
    val input: JsonObject,
    val reason: String? = null,
    val proposedAt: Long, // epoch millis
) : WireMessage

@Serializable
@SerialName("approval.responded")
data class ApprovalResponded(
    val approvalId: String,
    val decision: Decision, // ALLOW_ONCE | ALLOW_ALWAYS | DENY | CUSTOMIZE
    val customInput: JsonObject? = null,
) : WireMessage
```

注意：手机端 `ApprovalResponded.decision` → relay 必须映射成 CC stdin 的
`permission_response`；该映射键名属于未公开 schema，以 fixture 为准。

---

## 6. GOTCHAs（写代码前务必读）

1. **单连接单写者**：CC stdin 是一个 FIFO，任何并发写入都会撕裂一条 NDJSON 行。
   ApprovalBridge 与 ChatInteractor 必须共享**同一个** `StdinWriter`，并用
   `Mutex` / `@Synchronized` 序列化所有 `send(...)` 调用。
2. **stderr 独立**：CC 在 stderr 的 JSON banner 会被误解析。只从 stdout 拿 NDJSON，
   stderr 仅用于日志落盘。
3. **partial messages**：`--include-partial-messages` 打开后，`assistant` 事件可能分块到达，
   `CCEvent.Assistant.partial = true` 时**不要**当作终态；要等到同一 message 的
   `partial = false` 或下一条 `result` 再视为结束。若不需要增量 UI，建议**不要**加这个 flag。
4. **CC 版本漂移**：CC 更新可能引入新 `type`。`Unknown` 兜底负责不崩溃，但在生产
   应同时 `kermit.w` "unknown CCEvent type=$type"，便于触发 proposal 补类型。
5. **stdin backpressure**：CC 进程若阻塞（例如等待人工输入的遗留 prompt），stdin 写入
   会阻塞。`send(...)` 必须跑在专用 dispatcher 或协程里，不要占主事件循环。
6. **JSON 严格性**：`Json { ignoreUnknownKeys = true; isLenient = false }`，不要开 lenient，
   否则会吞掉真实 parse 错。

---

## 7. 测试策略

本 skill 对应 `openspec/specs/protocol/spec.md` 与 `openspec/specs/approval-inbox/spec.md`，
测试层次如下。

### 7.1 Unit（`shared/src/commonTest`）

```kotlin
package io.ohmymobilecc.core.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CCEventRoundTripTest {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    @Test
    fun unknownTypeFallsBackToUnknown() {
        val raw = """{"type":"future_event_xyz","foo":1}"""
        val ev = json.decodeFromString(CCEvent.serializer(), raw)
        assertIs<CCEvent.Unknown>(ev)
        assertEquals("future_event_xyz", ev.type)
    }

    @Test
    fun systemEventRoundTrips() {
        val original = CCEvent.System(subtype = "init")
        val line = json.encodeToString(CCEvent.serializer(), original)
        val back = json.decodeFromString(CCEvent.serializer(), line)
        assertEquals(original, back)
    }
}
```

### 7.2 Fixture 驱动（ApprovalBridge）

```kotlin
package io.ohmymobilecc.core.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class ApprovalBridgeFixtureTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun detectsPermissionRequestFromW06Fixture() {
        val lines = readResource("fixtures/permission_bash_request.ndjson")
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        val events = lines.map { json.decodeFromString(CCEvent.serializer(), it) }
        val req = events.filterIsInstance<CCEvent.PermissionRequest>()
        assertTrue(req.isNotEmpty(), "fixture must contain >=1 permission_request")
    }
}
```

### 7.3 真 claude 集成测试（`relay/src/test`）

```kotlin
package io.ohmymobilecc.relay.approval

import kotlin.test.Test

// Runs only when the developer opts in; CI default skips so PRs don't require
// a logged-in `claude` binary on the runner.
class ApprovalBridgeRealCCIT {
    @Test
    fun roundTripAllowDecisionUnblocksBashLs() {
        // 1. spawn `claude --bare -p ... -p "run ls /tmp via Bash"`
        // 2. wait for ApprovalRequested
        // 3. send ApprovalResponded(ALLOW_ONCE)
        // 4. assert final CCEvent.Result arrives within 30s
    }
}
```

运行方式：

```bash
RUN_REAL_CLAUDE=1 ./gradlew :relay:test --tests "*RealCCIT"
```

`RUN_REAL_CLAUDE` 未设置时，该类应通过 JUnit 条件（或 Kotest `condition`）跳过。

---

## 8. 相关材料

- Spec：`openspec/specs/protocol/spec.md`、`openspec/specs/approval-inbox/spec.md`
- Plan：`.claude/PRPs/plans/kmp-claude-code-remote.plan.md` 第 237–307、485–504 行
- 采集脚本：plan v2 Task 0.6（"采真实 CC permission NDJSON fixture"）
- 对外参考：Claude Code SDK docs <https://code.claude.com/docs/en/sdk>（只含总体流，permission schema 未文档化）
- 姊妹 skill：`docs/skills/openspec-workflow.md`（变更 `protocol` 时 proposal 的入口）
