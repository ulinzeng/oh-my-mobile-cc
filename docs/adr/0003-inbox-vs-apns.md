# ADR-0003 应用内 Inbox 审批而非 APNs 推送

- **状态**：Accepted
- **日期**：2026-04-22
- **决策者**：项目发起人 + 主 Claude agent
- **相关文件**：`openspec/project.md` §Domain Context、
  `.claude/PRPs/plans/kmp-claude-code-remote.plan.md` "Mobile Approval Inbox"、
  未来：`openspec/specs/approval-inbox/spec.md`

## Context（背景）

CC 的 `permission_request` 与 `AskUserQuestion` 都是**阻塞事件**：CC Agent 在
未收到响应之前会停在该事件上，Auto Mode 立刻失去意义。因此移动端必须**尽快**
把请求送到用户眼前，并把决策写回 CC 的 stdin。

行业惯用做法是 APNs / FCM 推送。但一期面对的约束是：

- 我们是**自托管**项目（见 ADR-0002），不想引入后端服务器
- APNs 需要：Apple Developer 账号 + 推送服务器（APNs HTTP/2 client） +
  证书 / token 轮换链路 —— 任何一环挂掉，审批都失败
- 一期分发走 TestFlight，不上 App Store，优先可交付而不是可扩展
- 用户画像：开发者，**愿意**主动打开 app 查看 Inbox；不是 2C 推送场景
- CC 的大多数 permission 事件发生在 Auto Mode 中，**桌面仍然在线**，所以
  至少存在一条 fallback 路径

## Decision（决策）

一期**采用应用内 Inbox** 形态：

- relay 在 `ApprovalBridge` 里捕获所有 `permission_request` / `AskUserQuestion`
  事件，分配 `approvalId`（UUID）、落库到本地 SQLite `approvals` 表
- relay 主动 push 给已连接的移动客户端（WireMessage.ApprovalRequested）
- 客户端 Inbox 页签以倒序列表展示未决项，角标显示数量
  - Android：前台 **Foreground Service** 保活轮询 / 连接，保证 app 在后台时
    仍有"最近一次决策"的本地可见性
  - iOS：**一期不做后台**；前台时 Inbox 实时刷新，后台时由用户下次打开 app
    时追上
- **10 分钟未响应自动 DENY**，并发送 `ApprovalExpired` 通知 relay
- "Allow Always for tool+session" 入 `approval_policies` 表，命中则自动 ALLOW
  不再打扰
- **不集成 APNs / FCM**
- **桌面 fallback**：relay 始终允许开发者在本机 CC 终端手动确认，作为
  兜底通道

## Alternatives Considered（备选方案）

### A. APNs / FCM 推送

- **放弃原因**：
  1. 需要推送服务器 —— 与"无公网后端"目标冲突
  2. 证书 / token 轮换运维成本
  3. 一期目标用户是开发者（自己打开 app 的频率高），性价比不足
  4. iOS 在 TestFlight 阶段推送链路的可靠性额外引入变量

### B. SMS / Email 通知

- **放弃原因**：
  1. 第三方服务成本
  2. 延迟 / 送达率不可控
  3. 用户还要在短信 / 邮件里再跳回 app，体验链过长

### C. 桌面弹窗 fallback（不做移动端）

- **放弃原因**：这正是问题本身 —— 用户不在桌面时无法批准；但可以**保留**
  作为 Inbox 的兜底通道

### D. Webhook + 用户自建推送网关

- **放弃原因**：一期不期望任何用户侧自建组件

## Consequences（后果）

### 正面

- **零后端成本**，无证书轮换
- iOS 审核表面积最小（无 Push Notification capability 申请）
- 用户数据 100% 留在本机 + Tailscale 内网
- 实现简单，便于集中精力打磨 Inbox UX

### 负面

- **用户未打开 app 时响应会延迟**，10 分钟后该请求被自动 DENY，Auto Mode
  会停在下一步依赖上；这条路径在 plan 的 Risks 表里记作
  "**Inbox 10 分钟超时误伤**"（概率中、影响中）
- iOS 后台能力弱 —— 不做 `BGProcessingTask` 意味着长时间锁屏会错过事件
- `always-allow` 策略一旦配错，会放行本不想批的工具调用 —— 需要 UI 上给
  "最近一次自动放行"的可追溯性

### 缓解措施

- Android Foreground Service 让"桌面在线、Tailscale 同网"场景下不丢事件
- 10 分钟超时**可配置**（per-tool 或全局），作为 policy 的一部分落库
- 所有自动 DENY 事件保留在 Inbox 历史中，带原因字段（`expired`）
- "Allow Always" 需要用户**显式**二次确认才落入 `approval_policies`

### 未来评估点

- 若运营数据显示"超时误伤"率高于阈值，则把 APNs 纳入二期评估；届时需要
  决定：自建推送服务器 vs 接入某个开源 self-hosted 推送（如 ntfy.sh）
- iOS 端是否值得引入 Silent Push + 通知服务扩展，视 Apple Developer 账号
  到位情况而定
- 若 Android 厂商后台限制严重（小米 / 华为），再讨论是否引入 FCM

## 参考

- 实施计划 "⚠️ Inbox 权衡（明示）" 节：已把超时 / 无后台风险写进上层文档
- 实施计划 "Risks" 表：`Inbox 10 分钟超时误伤` 已登记
- 未来的 `openspec/specs/approval-inbox/spec.md` 将把本决策落为契约
