# 项目上下文（Project Context）

> 本文件为 OpenSpec 真相源之一。修改时必须通过 `openspec/changes/<id>/` 流程，
> 不得直接改写跨越 `## Tech Stack` / `## Architecture Patterns` 的章节。

## 项目定位（Purpose）

`oh-my-mobile-cc` 是一套 **Kotlin Multiplatform** 跨端 Claude Code 客户端，目标是
让开发者在离开桌面时仍能通过手机继续驱动本机 Claude Code（以下简称 CC）Agent。

- **一期核心能力**：Chat + 终端观察 + 移动端审批/问答 Inbox（无 APNs）
- **分发**：iOS 走 TestFlight 内测优先；Android 走 AAB 直装
- **网络**：依赖 Tailscale mesh，不自建公网 relay
- **驱动方式**：移动客户端 → 桌面 relay → 本机 `claude -p --output-format stream-json --input-format stream-json`

## 技术栈（Tech Stack）

| 层 | 技术 | 版本约束 | 备注 |
|---|---|---|---|
| 共享语言 | Kotlin | 2.1.x | JVM target 17 |
| 跨端框架 | Kotlin Multiplatform | 稳定通道 | iOSX64 / iOSArm64 / iOSSimulatorArm64 / android / jvm |
| UI | Compose Multiplatform + SwiftUI（iOS 外壳） | 最新稳定 | Inbox/Chat/Terminal 纯原生自绘 |
| 协议 | kotlinx.serialization (JSON) + NDJSON 自定义 | 1.7.x | stream-json 事件流 |
| 网络 | Ktor (client + server) | 3.x | WebSocket over WSS，TLS 1.3 |
| 存储 | SqlDelight | 2.x | approvals / sessions / policies |
| PTY | pty4j（relay-only） | 最新 | JVM 原生 PTY |
| 测试 | kotlin.test + Kotest + MockK（JVM） | — | Red-Green-Refactor 强制 |
| 覆盖率 | kotlinx-kover | 0.7.x | `koverVerify` 门槛见下 |
| 静态检查 | detekt + ktlint | — | `./gradlew detekt ktlintCheck` |
| iOS 互操作 | SKIE | — | Flow/Sealed → Swift async/Enum |
| 文档 | OpenSpec CLI (`@fission-ai/openspec`) | 1.3.1+ | 真相源 |
| 日志 | kermit | — | 统一 KMP 日志 |
| 安全 | Ed25519（配对） + TLS 1.3 | — | 无硬编码 token |

## 目录与架构（Architecture Patterns）

采用 **Ports & Adapters + feature-first**：

```
shared/src/commonMain/kotlin/io/ohmymobilecc/
├── core/               # 纯领域（无框架依赖）
├── ports/              # 端口接口
├── adapters/           # 端口实现
├── features/           # 用例编排（interactor）
└── ui/                 # Compose 屏幕（feature 分组）

relay/src/main/kotlin/io/ohmymobilecc/relay/
├── cli/                # ClaudeProcess
├── approval/           # ApprovalBridge（拦截 CC permission）
├── pty/
├── fs/
├── server/
└── pairing/
```

### 约束
- `core/**` **禁止** import 任何框架（Ktor / Compose / pty4j / sqldelight）
- `features/**` 仅依赖 `core/**` + `ports/**`
- `adapters/**` 是端口唯一实现方；替换不影响 `features/**`
- `ui/**` 只调用 `features/**`，不直接碰 adapters

## 代码风格（Code Style）

- Kotlin 2.1，`explicitApi()` 在 `shared/` 启用
- 命名：类 `UpperCamel`，函数/变量 `lowerCamel`，常量 `UPPER_SNAKE`
- 不可变优先：`val` over `var`；数据层用 `data class` + `@Serializable`
- 并发：`kotlinx.coroutines` + `Flow`；禁用 `GlobalScope`
- 错误：统一领域错误 `RemoteError` sealed 层次；禁止 `throw Exception`
- 注释：**代码注释、KDoc 英文**；用户可见文案英文（可 i18n）
- 格式：`ktlintFormat` 为 pre-commit；CI `ktlintCheck` 必绿
- 文件头不写版权；每文件单公共入口

## 测试策略（Testing Strategy）

### TDD 强制
- **Red → Green → Refactor**，任何生产代码变更先提 failing test
- PR 在 squash 前须包含 `[red]` 与 `[green]` commits

### 测试金字塔
| 层 | 目标占比 | 工具 |
|---|---|---|
| Unit (core + parser + protocol) | 65% | kotlin.test + Kotest |
| Contract (port vs fake) | 20% | Kotest 双向 |
| Integration (relay + 真实 `claude --bare -p`) | 10% | JUnit |
| E2E | 5% | Maestro（Android）/ XCUITest（iOS） |

### 覆盖率门槛（Kover）
```
shared/core      ≥ 90%
shared/features  ≥ 80%
relay            ≥ 70%
ui/**            豁免
```

### 禁令
- 无测试 commit（docs-only 除外）
- 测试依赖真网络或真设备（integration/E2E 除外）
- `@Ignore` 超过 7 天必须删除或修复

## Git 工作流（Git Workflow）

- 主分支 `main` 保持可发布
- feature 分支命名：`feat/<capability>-<slug>`、`fix/<scope>-<slug>`、`docs/<scope>-<slug>`、`chore/<scope>-<slug>`
- 禁止 `--force` 推 main
- Commit 风格：Conventional Commits（`feat:`、`fix:`、`chore:`、`docs:`、`test:`、`refactor:`）
- Hook：SessionEnd 自动产生 CODEMAPS diff 与 CHANGELOG 追写（见 `.claude/scripts/session-end-docs.sh`）
- PR squash 前保留 `[red]` / `[green]` 历史

## 领域上下文（Domain Context）

- **CC stream-json** 事件流是强时序协议，消息类型至少包含：`system`、`user`、`assistant`、`result`、`permission_request`、`permission_response`、以及未来可能扩展的类型（需设计 `Unknown` fallback）
- **Permission / AskUserQuestion** 是阻塞事件：CC Agent 在未收到响应前会停在该事件
- **Approval Inbox** 是本项目替代官方桌面弹窗的核心设计；10 分钟超时自动 DENY
- **CC 能力复用**：MCP servers、hooks、slash commands、skills、Auto Mode 全部由本机 CC 负责，relay 只是薄壳

## 重要约束（Important Constraints）

- **非目标**：APNs / iOS 长期后台 / Computer Use / 自建公网 relay / 多人协作同 session / 大文件（>2MB）
- **Apple App Review 风险**：一期不上 App Store，TestFlight only
- **单连接假设**：一个 session 一个活动 WS，relay 做单写入锁
- **无凭据硬编码**：任何 token/IP 必须来自运行时配置或 SecureStoragePort
- **CC 版本漂移**：stream-json schema 未公开，需要在 W0.6 先采真实 NDJSON fixture

## 外部依赖（External Dependencies）

| 依赖 | 用途 | 备注 |
|---|---|---|
| 本机 Claude Code CLI | `claude -p --output-format stream-json` | relay 必须与 CC 同机 |
| Tailscale | mesh VPN，移动↔桌面 | 用户自行登录 |
| TestFlight | iOS 分发 | 证书/Provisioning 本地维护 |
| OpenSpec CLI | 文档/变更流程 | `npm i -g @fission-ai/openspec` |

## 文档语言（Documentation Language）

| 文件类型 | 语言 |
|---|---|
| README.md | English |
| README.zh-CN.md、AGENTS.md、openspec/**、docs/adr/**、docs/skills/** | 中文 |
| CHANGELOG.md | Conventional Commits（英文），章节双语可选 |
| 代码注释 / KDoc | English |
| 用户可见错误信息 | 英文（后续支持 i18n） |
