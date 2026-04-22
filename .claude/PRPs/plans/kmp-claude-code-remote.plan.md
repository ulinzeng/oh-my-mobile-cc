# Plan v2: KMP Cross-Platform Claude Code Remote Client (oh-my-mobile-cc)

> Revision date: 2026-04-22. Supersedes v1. User-confirmed deltas (v1 → v2):
> 1. **审批/问答为一期核心能力**：应用内 Inbox 形态（无 APNs，零后端成本）
> 2. **文档系统升级**：真正的 OpenSpec CLI（`@fission-ai/openspec` 1.3.1 已本机验证）+ skill 式渐进加载
> 3. **严格 TDD**：`Red → Green → Refactor`，PR 级 CI gate，覆盖率门槛
> 4. **代码架构目录化**：按 Ports & Adapters + feature-first 拆分
> 5. **多 agent 并行**：串行主线 + 热点并行（parser / encoder / review）
> 6. **需求可追溯**：所有需求 → OpenSpec proposal → spec → task → test，闭环
> 7. **SessionEnd hook**：openspec-validate、CODEMAPS 自动生成、CHANGELOG 追写
> 8. **分发**：iOS 走 TestFlight 内测优先
> 9. **文档语言**：README 英文 + 内部 specs/AGENTS 中文 + 代码注释英文

---

## Summary
用 Kotlin Multiplatform 构建一套 iOS + Android 客户端，通过 Tailscale 直连桌面端
relay 服务，由 relay 以 `claude -p --output-format stream-json --input-format
stream-json` 模式驱动本机原生 Claude Code CLI。一期**必包含**移动端审批/问答
交互（Inbox 模式）。项目采用 OpenSpec 驱动的文档系统，严格 TDD，热点并行。

## User Story
- **Primary**: As a developer, I want to drive my desktop Claude Code from my phone so that long-running agent tasks continue while I'm away from the desk.
- **Secondary**: As the same developer, I want to Allow/Deny CC permission prompts from my phone, so that Auto Mode does not stall when I'm mobile.
- **Tertiary**: As an agent working on this repo, I want all requirements captured as OpenSpec specs so that I can load only the relevant slice on demand.

## Problem → Solution
**Current**: CC 只在桌面终端；官方 Remote Control 仅 Pro/Max + 协议未公开 + 单连接；无移动审批能力；无 spec-first 研发规范。
**Desired**: 自托管 relay + 移动薄壳 + Inbox 审批 + OpenSpec + TDD，复用 CC 原生能力（MCP、hooks、skills、Auto Mode）。

## Metadata
- **Complexity**: XL
- **Estimated Files**: 80–120（含 openspec/、docs/、测试）
- **User-confirmed choices**:
  - Backend (1a): `claude -p --output-format stream-json --input-format stream-json`
  - Terminal (2b): Compose/SwiftUI 纯原生自绘（保留 W3 降级点）
  - Network (3a): 依赖 Tailscale
  - **Approval**: 应用内 Inbox
  - **Docs**: OpenSpec CLI (`@fission-ai/openspec` 1.3.1 已装)
  - **Parallel**: 串行主线 + 热点并行
  - **iOS**: TestFlight 内测优先
  - **Language**: README 英文 + 内部中文 + 代码注释英文

---

## UX Design

### Before
```
┌────────────────────────────────────┐
│  Laptop only                       │
│  $ claude  (permission prompts     │
│            block at desk)          │
└────────────────────────────────────┘
```

### After
```
┌─────────────────────────────┐        ┌──────────────────────────┐
│  iOS / Android              │  WSS   │  Desktop Relay           │
│  ┌────────┬────────┬──────┐ │ TLS1.3 │  ┌────────────────────┐  │
│  │ Chat   │ Term   │Files │─┼────────┼──│ claude -p stream-  │  │
│  ├────────┴────────┴──────┤ │  over  │  │ json --permission- │  │
│  │ Inbox (NEW)            │◀┼───────▶│  │ mode default       │  │
│  │  [•3] pending approvals│ │Tailscl │  └────────────────────┘  │
│  └────────────────────────┘ │        │  (local CC)              │
└─────────────────────────────┘        └──────────────────────────┘
```

### Interaction Changes (delta vs v1)
| Touchpoint | v1 | v2 |
|---|---|---|
| 权限请求 | 桌面弹窗人工点 | 手机 Inbox tab 卡片 Allow/Deny/Customize |
| 问答 AskUserQuestion | 桌面选项 | 手机 Inbox tab 选项列表，点选回传 |
| 长任务暂停原因 | "desktop pending" | Inbox 可见具体 prompt |

### Inbox Card (mockup)
```
┌─────────────────────────────────────┐
│ ⚠ Permission Request       14:32:05 │
│ Session: lint-fix-2026-04-22        │
│ Tool:   Bash                        │
│ Command: rm -rf node_modules/.cache │
│ Reason:  Clean build cache          │
│                                     │
│  [Deny]  [Allow Once]  [Customize]  │
└─────────────────────────────────────┘
```

---

## Documentation System (OpenSpec-driven)

### Directory Layout
```
oh-my-mobile-cc/
├── README.md                         # English
├── README.zh-CN.md                   # 中文说明
├── AGENTS.md                         # Agent 入口（中文）
├── openspec/                         # 真相源
│   ├── AGENTS.md
│   ├── project.md
│   ├── specs/
│   │   ├── protocol/spec.md
│   │   ├── approval-inbox/spec.md
│   │   ├── terminal/spec.md
│   │   ├── file-sync/spec.md
│   │   └── pairing/spec.md
│   └── changes/
│       └── <change-id>/
│           ├── proposal.md
│           ├── design.md
│           ├── tasks.md
│           └── specs/<capability>/spec.md
├── docs/
│   ├── CODEMAPS/                     # 自动生成
│   │   ├── shared.md
│   │   ├── relay.md
│   │   └── androidApp.md
│   ├── adr/
│   │   ├── 0001-kmp-choice.md
│   │   ├── 0002-tailscale-no-sdk.md
│   │   └── 0003-inbox-vs-apns.md
│   └── skills/                       # 渐进加载
│       ├── ansi-parser-deep-dive.md
│       ├── compose-canvas-terminal.md
│       ├── stream-json-protocol.md
│       └── openspec-workflow.md
├── CHANGELOG.md                      # 自动追写
├── .claude/
│   ├── settings.local.json
│   └── scripts/
│       ├── session-end-docs.sh
│       ├── gen-codemaps.sh
│       └── openspec-gate.sh
└── shared/ androidApp/ iosApp/ relay/
```

### Progressive Loading Pattern（类 skill）
每个 `docs/skills/*.md` 都以 YAML frontmatter 声明 triggers：
```markdown
---
name: ansi-parser-deep-dive
description: Use when implementing or modifying the VT100/ANSI state machine in shared/terminal/
triggers: ["ansi", "vt100", "csi", "escape sequence", "terminal parser"]
related-specs: [openspec/specs/terminal/spec.md]
---
```
Agent 根据任务内容动态加载对应 skill，不读无关文档。

### 文档语言规范
| 文件类型 | 语言 |
|---|---|
| README.md | English |
| README.zh-CN.md、AGENTS.md、openspec/**、docs/adr/**、docs/skills/** | 中文 |
| CHANGELOG.md | Conventional Commits（双语 section） |
| 代码注释 / KDoc | English |
| 用户错误信息 | 英文（可 i18n） |

---

## Architecture (Ports & Adapters + feature-first)

### shared/ 模块
```
shared/src/commonMain/kotlin/io/ohmymobilecc/
├── core/                       # 纯领域（无框架依赖）
│   ├── protocol/               # CCEvent, WireMessage, NdJson
│   ├── approval/               # ApprovalRequest, ApprovalDecision
│   ├── terminal/               # AnsiParser, TerminalState, Grid
│   ├── session/                # SessionId, SessionMeta
│   └── error/                  # RemoteError
├── ports/                      # 端口接口
│   ├── SecureStoragePort.kt
│   ├── TransportPort.kt
│   ├── PtyPort.kt
│   └── ClockPort.kt
├── adapters/
│   ├── transport/              # Ktor WS
│   ├── storage/                # SqlDelight
│   └── crypto/                 # Ed25519
├── features/                   # 用例编排
│   ├── chat/ChatInteractor.kt
│   ├── approval/ApprovalInteractor.kt
│   ├── terminal/TerminalInteractor.kt
│   └── files/FileInteractor.kt
└── ui/
    ├── chat/
    ├── terminal/
    ├── files/
    └── inbox/
```

### relay/ 模块
```
relay/src/main/kotlin/io/ohmymobilecc/relay/
├── cli/ClaudeProcess.kt
├── approval/ApprovalBridge.kt
├── pty/Pty.kt
├── fs/FsBridge.kt
├── server/RelayServer.kt
└── pairing/PairingService.kt
```

### TDD 架构收益
- `core/**` 无框架依赖 → 90% 覆盖由 commonTest 提供
- 端口注入使 adapters 可 fake → 集成测试无真网络依赖

---

## TDD Discipline（强制）

### Red → Green → Refactor
- 任何生产代码变更必须先有 failing test commit
- PR 必须含 `[red]` + `[green]` commit 历史（squash 前）
- CI gate：
  ```
  ./gradlew :shared:allTests :relay:test
  ./gradlew koverHtmlReport koverVerify
  ```

### Test Pyramid
| 层 | 占比 | 工具 |
|---|---|---|
| Unit (core + parser + protocol) | 65% | kotlin.test + Kotest |
| Contract (port vs fake) | 20% | Kotest 双向 |
| Integration (relay + real claude) | 10% | `claude --bare -p` |
| E2E | 5% | Maestro / XCUITest |

### 禁令
- 无测试 commit（docs-only 除外）
- 测试依赖真网络
- `@Ignore` 超过 7 天

---

## Mobile Approval Inbox（一期新核心）

### 数据流
```
claude -p stream-json 输出:
  {"type":"permission_request","tool":"Bash","input":{"command":"rm -rf x"},...}
                    │
                    ▼
relay/approval/ApprovalBridge
  - 分配 approvalId (uuid)
  - 持久化到 SQLite approvals 表
  - 广播 WireMessage.ApprovalRequested
                    │
                    ▼
shared/features/approval/ApprovalInteractor
  - StateFlow<List<ApprovalRequest>>
                    │
                    ▼
ui/inbox/InboxScreen
  - 展示 Inbox 列表
  - 用户点 Allow/Deny/Customize → WireMessage.ApprovalResponded
                    │
                    ▼
relay 收到决策 → 写入 claude stdin stream-json
  {"type":"permission_response","request_id":"...","decision":"allow"}
```

### WireMessage 扩展
```kotlin
@Serializable @SerialName("approval.requested")
data class ApprovalRequested(
    val approvalId: String,
    val sessionId: String,
    val tool: String,
    val input: JsonObject,
    val reason: String? = null,
    val proposedAt: Long              // epoch millis
): WireMessage

@Serializable @SerialName("approval.responded")
data class ApprovalResponded(
    val approvalId: String,
    val decision: Decision,           // ALLOW_ONCE | ALLOW_ALWAYS | DENY | CUSTOMIZE
    val customInput: JsonObject? = null
): WireMessage

@Serializable @SerialName("approval.expired")
data class ApprovalExpired(val approvalId: String, val reason: String): WireMessage
```

### Inbox 行为
- 列表倒序
- 角标（Android badge / iOS 前台徽标）
- 10 分钟超时自动 DENY + expired
- "Allow Always for tool+session" 入 `approval_policies` 表

### ⚠️ Inbox 权衡（明示）
- **无 APNs**：用户未打开 app 时，10 分钟后超时 → Auto Mode 暂停
- 缓解：Android Foreground Service（Tailscale 同网保活）；iOS 本期不做后台
- 桌面 fallback：relay 允许手动在 CC 终端确认

### ⚠️ W0 必须前置实验（采真实 NDJSON）
CC stream-json 的 permission 事件 schema 未公开文档化，W0.6 必须：
```bash
mkdir -p shared/src/commonTest/resources/fixtures
claude --bare -p --output-format stream-json --input-format stream-json \
  --permission-mode default --include-partial-messages \
  -p "use Bash tool to run: ls /tmp" \
  > shared/src/commonTest/resources/fixtures/permission_bash_request.ndjson
```
所有 `ApprovalBridgeTest`、`CCEventTest.permissionRequest` 以此 fixture 为准。

---

## Mandatory Reading

| Priority | File | Why |
|---|---|---|
| P0 | `openspec/project.md`（init 后生成） | tech stack + conventions |
| P0 | `AGENTS.md`（根） | agent 工作流 + 文档导航 |
| P0 | `openspec/specs/**` | 不变真相 |
| P0 | `openspec/changes/<active>/tasks.md` | 当前 sprint |
| P0 | PeopleInSpace `common/build.gradle.kts`、`settings.gradle.kts` | KMP 样板 |
| P0 | `claude --help` 采集片段 | stream-json flags |
| P1 | `docs/skills/*.md`（按 trigger） | 按需加载 |
| P1 | OpenSpec 官方 `AGENTS.md` 模板 | 工作流规约 |
| P2 | `docs/adr/**` | 历史决策 |

## External Documentation

| Topic | Source | Key |
|---|---|---|
| OpenSpec CLI | https://github.com/Fission-AI/OpenSpec | `openspec` 1.3.1 本机已装 |
| Claude Code SDK stream-json | https://code.claude.com/docs/en/sdk | 事件流 + permission schema |
| PeopleInSpace | /Users/ulinzeng/Documents/PeopleInSpace | 本机 KMP 参考 |
| pty4j | github.com/JetBrains/pty4j | JVM PTY |
| Ktor | ktor.io | KMP WS |
| SKIE | skie.touchlab.co | Swift 互操作 |
| Kover | github.com/Kotlin/kotlinx-kover | Kotlin 覆盖率 |

---

## Patterns to Mirror

### KMP_MODULE_TARGETS
```kotlin
// SOURCE: PeopleInSpace/common/build.gradle.kts:28-55
kotlin {
    jvmToolchain(17)
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "shared" }
    }
    androidTarget(); jvm()
}
```

### TDD_TEST_STRUCTURE
```kotlin
// shared/src/commonTest/kotlin/io/ohmymobilecc/core/approval/ApprovalInteractorTest.kt
class ApprovalInteractorTest {
    private val transport = FakeTransport()
    private val clock = FakeClock()
    private val sut = ApprovalInteractor(transport, clock)

    @Test fun `expires request after 10 minutes`() = runTest {
        transport.receive(ApprovalRequested("a1", "s1", "Bash", JsonObject(emptyMap()), null, 0))
        clock.advance(10.minutes + 1.seconds)
        assertEquals(Expired("a1"), sut.state.value.first())
    }
}
```

### OPENSPEC_PROPOSAL_TEMPLATE
```markdown
# openspec/changes/add-approval-inbox/proposal.md
## Why
移动端无法响应 CC 权限请求导致 Auto Mode 停滞。

## What Changes
- 新增 capability approval-inbox
- 扩展协议 WireMessage.Approval*
- relay/approval/ApprovalBridge 拦截 stream-json permission events
- ui/inbox/InboxScreen

## Impact
- Affected specs: protocol, pairing
- Affected code: relay/approval/**, shared/features/approval/**, shared/ui/inbox/**
```

### SESSION_END_HOOK（settings.local.json）
```json
{
  "hooks": {
    "SessionEnd": [
      {
        "matcher": "*",
        "hooks": [
          { "type": "command", "command": ".claude/scripts/session-end-docs.sh" }
        ]
      }
    ]
  }
}
```
`session-end-docs.sh` 职责：
1. `openspec validate` → 失败告警（不阻断）
2. 扫 `openspec/changes/*/tasks.md` → 打印 "ready to archive" 提示
3. `gen-codemaps.sh` → 产出 `docs/CODEMAPS/*.md`
4. `git diff --stat` + conventional commit 汇总 → append `CHANGELOG.md`
5. 所有写入先 diff 打印；`PENDING_DOCS_ONLY=1` 时仅写 `.claude/pending-docs/`

---

## Files to Change（新项目全部 CREATE）

| File | Action | Justification |
|---|---|---|
| `README.md` / `README.zh-CN.md` | CREATE | 英文对外 + 中文速读 |
| `AGENTS.md` | CREATE | Agent 入口 |
| `openspec/` | CREATE via `openspec init` | 官方 CLI 生成 |
| `openspec/specs/protocol/spec.md` | CREATE | 协议契约 |
| `openspec/specs/approval-inbox/spec.md` | CREATE | 审批能力 |
| `openspec/specs/terminal/spec.md` | CREATE | ANSI + 自绘 |
| `openspec/specs/file-sync/spec.md` | CREATE | 文件增量 |
| `openspec/specs/pairing/spec.md` | CREATE | 配对 + Tailscale |
| `openspec/changes/bootstrap/*` | CREATE | 初始 proposal |
| `docs/adr/0001..0003.md` | CREATE | 架构决策 |
| `docs/skills/*.md` | CREATE | 渐进加载 4 篇 |
| `docs/CODEMAPS/*.md` | AUTO-GEN | hook |
| `CHANGELOG.md` | CREATE + AUTO | hook |
| `.claude/scripts/session-end-docs.sh` | CREATE | SessionEnd hook |
| `.claude/scripts/gen-codemaps.sh` | CREATE | CODEMAPS 生成 |
| `.claude/settings.local.json` | UPDATE | 注册 hook |
| `settings.gradle.kts`、`build.gradle.kts`、`gradle/libs.versions.toml` | CREATE | Gradle 骨架 |
| `shared/build.gradle.kts` | CREATE | KMP + Kover + detekt |
| `shared/src/commonMain/kotlin/io/ohmymobilecc/core/**` | CREATE | 纯领域 |
| `shared/src/commonMain/kotlin/io/ohmymobilecc/features/approval/**` | CREATE | 审批用例 |
| `shared/src/commonMain/kotlin/io/ohmymobilecc/ui/inbox/**` | CREATE | Inbox UI |
| `shared/src/commonTest/**` | CREATE | RED 先于 GREEN |
| `relay/src/main/kotlin/.../approval/ApprovalBridge.kt` | CREATE | 拦截 CC 事件 |
| `relay/src/test/...` | CREATE | 含真 claude 集成测试 |
| `androidApp/src/main/.../InboxScreenHost.kt` | CREATE | Android shell |
| `iosApp/iosApp/InboxHost.swift` | CREATE | iOS shell |
| `maestro/flows/inbox-allow.yaml` | CREATE | E2E 烟测 |

## NOT Building（显式）
- APNs push
- iOS BGProcessingTask 长期后台
- Computer Use (VNC)
- 自建公网 relay server
- 多人协作同 session
- 大文件 >2MB 编辑
- macOS Mac Catalyst
- App Store 公开上架

---

## Step-by-Step Tasks

> 图例：🅟 = 并行热点；🅢 = 强制串行；`[RED]/[GREEN]/[REFACTOR]` = TDD 阶段

### Phase W0 — 文档与基础设施（串行，2–3 天）

#### Task 0.1 🅢 初始化 OpenSpec
- **ACTION**: `openspec init`
- **VALIDATE**: `openspec list` 可运行

#### Task 0.2 🅢 写 bootstrap proposal
- **ACTION**: `openspec/changes/bootstrap/proposal.md` 声明一期全部 capabilities
- **VALIDATE**: `openspec validate bootstrap` 通过

#### Task 0.3 🅢 建立 Gradle 骨架
- **ACTION**: settings/build/libs.versions.toml；启用 Kover + detekt + ktlint
- **VALIDATE**: `./gradlew :shared:build`

#### Task 0.4 🅢 SessionEnd hook
- **ACTION**: 写 `.claude/scripts/session-end-docs.sh` + 注册
- **VALIDATE**: 手工 `exit` 触发

#### Task 0.5 🅟 4 篇 skill 文档（派 2 agent 并行）
- `docs/skills/openspec-workflow.md`、`stream-json-protocol.md`、`ansi-parser-deep-dive.md`、`compose-canvas-terminal.md`

#### Task 0.6 🅢 采真实 CC permission NDJSON fixture
- **ACTION**: 见 "前置实验" 节
- **VALIDATE**: fixture 至少含一条 permission event

---

### Phase W1 — 协议 + 审批流贯通（串行为主，5–7 天）

#### Task 1.1 🅢 [RED] 写 CCEvent/WireMessage 测试契约
- **ACTION**: commonTest 先写 round-trip、unknown type fallback、ApprovalRequested 序列化测试
- **VALIDATE**: 全部 fail

#### Task 1.2 🅟 [GREEN] 实现 CCEvent + WireMessage
- **ACTION**: agent-A 实现 CCEvent，agent-B 实现 WireMessage；sealed base 由主 Claude 先定稿
- **VALIDATE**: 1.1 测试全绿

#### Task 1.3 🅟 [RED+GREEN] NDJSON Flow + ClaudeProcess（relay）
- **GOTCHA**: stderr 独立；stdin 每条 `\n + flush`

#### Task 1.4 🅢 [RED+GREEN] ApprovalBridge（本期关键）
- **ACTION**: 使用 W0.6 fixture 写测试 + 实现
- 包含 `timesOutAfter10Min`、`relaysDecisionToStdin`

#### Task 1.5 🅢 [RED+GREEN] Pairing + RelayClient
- 6 位配对码 + Ed25519（同 v1）

---

### Phase W2 — Chat + Inbox UI（5–7 天）

#### Task 2.1 🅟 Chat + Inbox interactor（2 agent 并行）
#### Task 2.2 🅢 Compose MP UI：ChatScreen + InboxScreen
#### Task 2.3 🅢 iOS/Android shell + SqlDelight approvals 表
#### Task 2.4 🅟 E2E（Maestro + XCUITest 并行）

---

### Phase W3 — 终端（10–14 天，最高风险）

- Task 3.1（ANSI parser）与 3.2（Grid model）派 2 agent 并行
- W3 第 7 天 checkpoint：`htop` 不稳则切 xterm.js

---

### Phase W4 — Files + 打磨（5–7 天）

- Task 4.6 🅢 TestFlight 上传
- Task 4.7 🅢 `openspec archive bootstrap`

---

## Testing Strategy

### Unit Tests
| Test | Layer | Covers |
|---|---|---|
| CCEventTest | protocol | system/user/assistant/result + unknown fallback |
| WireMessageTest | protocol | 所有 op 含 approval.* |
| AnsiParserTest | terminal | VT500 + UTF-8 + CJK + emoji |
| ApprovalInteractorTest | features | 排队、超时、policy、并发 |
| ApprovalBridgeTest | relay | 事件识别、stdin 回写、timeout |
| PairingTest | transport | 码失效、replay 拒绝、签名 |
| SessionStoreTest | adapters | SqlDelight CRUD、resume |
| FileSyncTest | features | Myers diff |

### Contract / Integration / E2E
- Transport/SecureStorage 双向 fake
- relay + real `claude --bare -p`
- Maestro / XCUITest

### Coverage Gate
```
shared/core      ≥ 90%
shared/features  ≥ 80%
relay            ≥ 70%
ui 豁免
```

---

## Validation Commands

```bash
# Static
./gradlew :shared:detekt :relay:detekt :shared:ktlintCheck

# Tests
./gradlew :shared:allTests :relay:test

# Coverage
./gradlew koverHtmlReport koverVerify

# OpenSpec
openspec list
openspec validate --strict

# Relay smoke
./gradlew :relay:shadowJar
java -jar relay/build/libs/relay-all.jar --port 7788 &

# Android E2E
maestro test maestro/flows/inbox-allow.yaml

# iOS E2E
xcodebuild test -scheme iosAppUITests -destination 'platform=iOS Simulator,name=iPhone 15'
```

### Manual Validation
- [ ] 手机触发 CC `Bash rm -i file.tmp` → Inbox 收到 → Allow → CC 继续
- [ ] 超时 10 分钟 → expired
- [ ] `openspec validate` 绿
- [ ] SessionEnd hook：`/exit` → 看到 CODEMAPS diff + CHANGELOG 新行

---

## Acceptance Criteria
- [ ] W0–W4 全完成
- [ ] 覆盖率达标
- [ ] `openspec validate` 绿
- [ ] Inbox 审批端到端打通（含超时）
- [ ] iOS TestFlight 可用
- [ ] Android AAB 可签
- [ ] SessionEnd hook 3 项实跑正常

## Completion Checklist
- [ ] Ports & Adapters + feature-first
- [ ] 所有需求有 spec
- [ ] 每 feature 至少 1 篇 skill 文档
- [ ] 代码注释英文、spec 中文、README 英文
- [ ] RemoteError 统一
- [ ] kermit 日志
- [ ] 无硬编码 IP/token
- [ ] CHANGELOG 追到最新

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| 自绘终端 W3 卡住 | 中 | 高 | 第 7 天降级 xterm.js |
| CC stream-json permission schema 与假设不符 | 高 | 高 | W0.6 采真实 NDJSON |
| OpenSpec 对快节奏开发负担 | 中 | 中 | 小改不开 proposal |
| 并行 agent 协议冲突 | 中 | 中 | 🅟 前主 Claude 先定接口 |
| Inbox 10 分钟超时误伤 | 中 | 中 | per-tool 可配；always policy |
| TestFlight 90 天过期 | 低 | 中 | 续期 CI（future） |
| TDD gate 慢 | 中 | 低 | spike 分支不入 main |

## Notes
- v1 → v2 Delta：Inbox + OpenSpec + TDD + Ports & Adapters + 并行策略 + SessionEnd hook + TestFlight
- 1.txt 修正延续：`claude -p stream-json`（非 `claude code serve`）；无 Kterm / Compose CodeView / KMP Tailscale SDK
- W0.6 采真实 CC permission NDJSON 是 W1.4 的强前置
- 下一步：确认 v2 → 开始 Phase W0
