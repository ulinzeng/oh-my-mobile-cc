---
status: accepted
date: 2026-04-22
depends-on: []
---

# ADR-0001 选择 Kotlin Multiplatform

- **状态**：Accepted
- **日期**：2026-04-22
- **决策者**：项目发起人 + 主 Claude agent
- **相关文件**：`openspec/project.md` §Tech Stack、`README.md`、实施计划
  `.claude/PRPs/plans/kmp-claude-code-remote.plan.md`

## Context（背景）

`oh-my-mobile-cc` 需要**同时**覆盖 iOS 与 Android 两端，一期目标是 Chat +
终端观察 + 移动审批 Inbox。团队的 Kotlin 背景最深，而且桌面 relay 本身就是
JVM 进程（Ktor + pty4j），若客户端也能共享 Kotlin 代码，就可以在**协议 / 状态
机 / 业务用例**这三类最容易写错的部分做一次性建模，避免双端各实现一份。

关键约束：

- stream-json 事件 schema 未公开且可能漂移 —— 需要 **一份** protocol 模型给
  手机与 relay 共同使用，避免两边 drift
- Approval Inbox 状态机（pending → responded / expired）需要跨端一致行为，
  且必须能被 100% 单测覆盖（见覆盖率门槛 `shared/core ≥ 90%`）
- UI 层一期不追求双端外观统一：iOS 习惯 SwiftUI，Android 习惯 Compose
- 分发走 TestFlight + AAB，不上公开 App Store，对包体与冷启动不敏感

## Decision（决策）

采用 **Kotlin Multiplatform (KMP)** 作为主技术栈：

- `shared/` 模块覆盖 `iosX64`、`iosArm64`、`iosSimulatorArm64`、`androidTarget`、
  `jvm`（供 relay 和桌面测试复用）
- UI 层：
  - Android / desktop 测试端用 **Compose Multiplatform**
  - **iOS 壳用原生 SwiftUI**，通过 **SKIE** 消费 `shared` framework（把
    `Flow` / `sealed class` 映射成 Swift `AsyncSequence` / `enum`）
- `core/`（协议、approval 状态机、ANSI parser、session meta）**禁止**引入任何
  框架依赖，纯 Kotlin，从而天然被 commonTest 充分覆盖

## Alternatives Considered（备选方案）

### A. Flutter

- **理由**：单一代码库双端、UI 一致
- **放弃原因**：
  1. Dart 生态与本项目的 JVM relay 栈完全脱节 —— 协议模型要在 Dart 和
     Kotlin 两边各写一遍，与"一份协议真相源"的目标冲突
  2. 团队无 Dart 深度经验，学习成本浪费在非核心
  3. 与 SKIE 类互操作工具相比，Dart ↔ 原生 Swift 的桥接更笨重

### B. React Native

- **理由**：生态活跃、热重载好
- **放弃原因**：
  1. JavaScript 栈与 JVM relay、Kotlin 协议模型完全脱节
  2. iOS 审核中 RN 与 Hermes 偶发问题，一期要走 TestFlight，不想额外负担
  3. 终端 / ANSI 解析这类偏 CPU 密集的逻辑在 RN 桥接层表现不佳

### C. 纯原生双栈（Swift + Kotlin 各写一套）

- **理由**：每端都拿到最理想的体验
- **放弃原因**：
  1. 协议、状态机、ANSI parser 重复实现，必然 drift —— 这是项目**最怕**
     出现的 bug 形态
  2. Inbox 超时、policy 合并等逻辑的单测成本翻倍
  3. 团队规模小，不具备长期维护双栈的带宽

### D. 纯 Compose Multiplatform（含 iOS）

- **放弃原因**：Compose iOS 目前仍在 beta，打包体积与启动时间对 TestFlight
  分发欠佳；一期选择更保守的 SwiftUI 壳 + KMP 逻辑共享

## Consequences（后果）

### 正面

- 协议 / 状态机只写一次，天然一致
- `core/**` 无框架依赖，单测成本低 → 90% 覆盖门槛可达
- 可以用 `jvm` target 跑所有 commonTest，CI 快
- relay 与 shared 都是 Kotlin，未来若需要把 `ApprovalBridge` 抽到 shared 也容易

### 负面

- KMP 对 iOS 的打包、Xcode 集成、Cocoapods / SPM 有一定学习成本
- Compose iOS 还在 beta —— 本项目**刻意**不用，iOS 走 SwiftUI，但要接受
  "两端 UI 代码不共享"的事实
- 跨端调试需要同时装 Android Studio 与 Xcode，工位门槛高一点
- SKIE 是第三方依赖，需关注其与 Kotlin 版本的兼容节奏

### 后续

- 若 W3 发现自绘终端在 iOS 上 Compose 自绘的 beta 问题难绕开，要重新评估
  "把 Terminal 也用 SwiftUI 绘制，共享仅到 `TerminalState`"的方案
- Compose Multiplatform for iOS 若转稳定，可评估迁移 UI 层（非一期目标）

## 参考

- JetBrains KMP 官方文档
- 本机参考项目：`/Users/ulinzeng/Documents/PeopleInSpace`
- SKIE：<https://skie.touchlab.co>
