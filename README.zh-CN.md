# oh-my-mobile-cc（中文说明）

> 把桌面上的 Claude Code 装进手机 —— 不只是观看终端，还能**直接审批权限**。

**项目状态**：W0 bootstrap 阶段，尚不可运行。

## 问题

Claude Code（下简称 CC）是桌面级 Agent，但它的 `permission_request` 与
`AskUserQuestion` 事件**会阻塞在本地终端**上。一旦你离开电脑，Auto Mode 就停
在某个 `Bash rm ...` 的询问处，直到你回到键盘为止。官方 Remote Control 仅限
Pro/Max 订阅、只允许单连接、协议未公开，所以**没有官方途径**让你在手机上
批准一条 shell 命令。

## 方案

`oh-my-mobile-cc` 是一套基于 **Kotlin Multiplatform** 的 iOS + Android 客户端，
连接到你自己在桌面端跑的一个本地 relay 进程。relay 封装的是：

```
claude -p --output-format stream-json --input-format stream-json
```

CC 的事件流通过 WebSocket 传给手机，整条链路跑在你自己的 **Tailscale**
内网里 —— 不需要公网服务器，不集成 VPN SDK，也不依赖 APNs。

手机端提供专门的 **Approval Inbox** 页签：每当 CC 抛出 `permission_request`
或 `AskUserQuestion`，就会在手机上出现一张卡片，按钮为
`Allow Once` / `Deny` / `Customize`。点选后响应会写回 CC 的 stdin，Auto Mode
即可在你移动过程中继续跑。

## 架构图

```
┌─────────────────────────────┐        ┌──────────────────────────┐
│  iOS / Android              │  WSS   │  Desktop Relay           │
│  ┌────────┬────────┬──────┐ │ TLS1.3 │  ┌────────────────────┐  │
│  │ Chat   │ Term   │Files │─┼────────┼──│ claude -p stream-  │  │
│  ├────────┴────────┴──────┤ │  over  │  │ json --permission- │  │
│  │ Inbox                  │◀┼───────▶│  │ mode default       │  │
│  │  [•3] pending approvals│ │Tailscl │  └────────────────────┘  │
│  └────────────────────────┘ │        │  (本机 CC)               │
└─────────────────────────────┘        └──────────────────────────┘
```

共享 Kotlin 代码采用 **Ports & Adapters + feature-first**：`core/` 不引任何
框架，`features/` 负责编排用例，`adapters/` 是唯一接触 Ktor / SqlDelight /
pty4j 的地方。

## 功能

| 功能            | 状态      | 说明                                                 |
| --------------- | --------- | ---------------------------------------------------- |
| Chat            | [planned] | 完整 CC 对话，relay 流式转发。                       |
| Terminal viewer | [planned] | Compose/SwiftUI 纯自绘 ANSI 网格（W3 实现）。        |
| Approval Inbox  | [planned] | Allow / Deny / Customize，10 分钟超时。              |
| File browser    | [planned] | 通过 relay 读文件 + 小文件编辑（<2MB）。             |
| Pairing         | [planned] | 6 位配对码 + Ed25519，禁止硬编码 token。             |

## 非目标

一期明确**不做**（见 `docs/adr/0003-inbox-vs-apns.md`）：

- APNs / Firebase push 推送
- iOS 长时后台任务（`BGProcessingTask`）
- Computer Use / VNC 形式的远程桌面
- 自建公网 relay server
- 同一 session 多人协作
- 大文件编辑（>2MB）
- Mac Catalyst 以及 App Store 公开上架

## 模块布局

| 模块          | 目标平台              | 职责                                                              |
| ------------- | --------------------- | ----------------------------------------------------------------- |
| `shared/`     | KMP（JVM/iOS/Android） | `core/` 协议与领域、`ports/`、`adapters/`、`features/`、`ui/`（Compose MP 用于 Android/desktop）。 |
| `androidApp/` | Android               | 薄壳：Compose host + Foreground Service 承载 Inbox 轮询。         |
| `iosApp/`     | iOS                   | SwiftUI 壳，通过 SKIE 使用 `shared` framework。                   |
| `relay/`      | JVM（desktop）         | 封装 `claude -p`，承载 `ApprovalBridge`、`Pty`、`FsBridge`、`RelayServer`、`PairingService`。 |

## 前置条件

- JDK 17（Kotlin JVM toolchain 目标）
- Android SDK 34+（构建 `androidApp`）
- Xcode 15+（构建 `iosApp`，走 TestFlight）
- Node 20+（仅用于 OpenSpec CLI，`@fission-ai/openspec` 1.3.1+）
- 在**手机与桌面两端**登录 [Tailscale](https://tailscale.com)
- 桌面上安装并可用的原生 **`claude` CLI**

## 快速开始（W0 脚手架阶段，尚不可运行）

W0 阶段没有终端用户可用流程，下面的命令仅用于验证脚手架：

```bash
# 验证 shared KMP 模块在 JVM 目标可编译
./gradlew :shared:compileKotlinJvm

# 浏览 OpenSpec 真相源
openspec list

# 启动桌面 relay（传输尚未接入）
./gradlew :relay:run
```

W1 完成后才会有真正的链路：先启动 relay → 手机配对 → 打开 Inbox 页签。

## 文档导航

| 入口                          | 读者         | 内容                                                       |
| ----------------------------- | ------------ | ---------------------------------------------------------- |
| `README.md`                   | 英文读者     | 英文版 README                                              |
| `AGENTS.md`                   | AI agent     | Agent 工作流、导航图、TDD 纪律                             |
| `openspec/project.md`         | 所有人       | 技术栈与架构约束（真相源）                                 |
| `openspec/specs/**`           | 所有人       | 按 capability 拆分的 spec（protocol、approval-inbox 等）   |
| `openspec/changes/<id>/`      | 所有人       | 尚未归档的变更提案                                         |
| `docs/adr/`                   | 所有人       | 架构决策记录（KMP 选型、Tailscale、Inbox）                 |
| `docs/skills/`                | AI agent     | 按关键字触发的渐进加载技能                                 |
| `.claude/PRPs/plans/`         | 维护者       | 驱动 W0–W4 的 v2 实施计划                                  |

## 许可证

**TBD —— 尚未决定，请勿再分发。**
