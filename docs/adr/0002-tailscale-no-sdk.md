---
status: accepted
date: 2026-04-22
depends-on: []
---

# ADR-0002 依赖 Tailscale 但不集成 SDK

- **状态**：Accepted
- **日期**：2026-04-22
- **决策者**：项目发起人 + 主 Claude agent
- **相关文件**：`openspec/project.md` §External Dependencies、
  `README.md` "Prerequisites"、实施计划
  `.claude/PRPs/plans/kmp-claude-code-remote.plan.md` "User-confirmed choices / Network (3a)"

## Context（背景）

移动客户端需要**穿越 NAT** 访问运行在家用 / 办公桌面 / 笔记本上的 relay 进程。
候选路径通常是：

1. 用户自建**公网 relay server** 做中转
2. 用 **TURN/STUN** 自建打洞服务
3. 借助某种**现成的 mesh VPN**（Tailscale / Nebula / Cloudflare Tunnel 等）
4. 把 mesh VPN SDK **内嵌**进 app

一期项目约束：

- 目标是"**轻壳**" —— 不想承担服务器运维、证书续期、合规备案成本
- iOS 要走 TestFlight 内测，**不希望引入 VPN / Network Extension 权限**，那
  会显著拉长审核周期和引入额外的 entitlement 申请
- 用户画像是"自己本机有 `claude` CLI 的开发者"，他们**极有可能已经用过或
  愿意装 Tailscale**
- 项目方不想替用户持有任何公网凭据

## Decision（决策）

- **运行时依赖**：用户在**手机与桌面两端**都登录 Tailscale（官方 app），
  二者处于同一 tailnet 中
- **访问路径**：app 直接通过 `http(s)://<tailnet-name-or-magicdns>:<port>` 访问
  本机 relay，相当于一次普通的局域网 HTTP 请求
- **不集成 Tailscale SDK**：app 内不链接 `libtailscale` / `tsnet`，不申请任何
  VPN / Network Extension 权限，也不维护 Tailscale 认证流程
- **relay 侧**：只监听本机回环或 Tailscale 接口，不绑定公网 IP；TLS 1.3 + 6
  位配对码 + Ed25519 仍然执行（见 pairing spec）

## Alternatives Considered（备选方案）

### A. 内嵌 Tailscale SDK（`tsnet` / `libtailscale`）

- **理由**：用户体验一致，不用单独装 Tailscale app
- **放弃原因**：
  1. iOS 集成复杂，涉及 Network Extension、entitlement，显著**延长 App
     Review**，与 TestFlight-first 目标冲突
  2. 增加包体与启动开销，与"轻壳"定位不符
  3. 维护 Tailscale 版本、授权、登录流程是额外负担
  4. 用户若已是 Tailscale 用户，嵌入式 SDK 会与其设备上 Tailscale 应用产生
     配置冲突

### B. 自建 TURN/STUN 打洞 + 公网 relay

- **放弃原因**：
  1. 服务器运维 / 费用 / 安全责任
  2. 把项目从"自托管轻壳"变成"平台服务"，与非目标列表冲突
  3. 要做 TLS、限流、滥用检测，工作量远超一期预算

### C. Cloudflare Tunnel / ngrok / FRP 等隧道服务

- **放弃原因**：
  1. 绑定第三方平台，违反"无外部账户依赖"的直觉
  2. 增加一跳 hop，延迟与可靠性下降
  3. 对免费额度 / 限流敏感，不适合长会话 stream-json

### D. LAN-only（仅限同 Wi-Fi）

- **放弃原因**：核心用户故事就是"离开桌子也要能批准"，同网段限制直接
  否定主用例

## Consequences（后果）

### 正面

- **不申请 VPN / Network Extension 权限** → iOS 审核路径最短，符合
  TestFlight-first
- 开发者不需要维护任何公网服务
- 安全边界极小 —— relay 只暴露在 tailnet 内
- 用户的 Tailscale ACL 天然成为我们的访问控制层

### 负面

- **强前置**：用户必须会装 / 登录 Tailscale，多一道 onboarding 门槛
- 若 Tailscale 服务（控制面）故障，本项目不可用；但设备对等流量仍可走，
  问题局限在新设备登录
- 我们不能对"用户在公网的 VPN 体验"做任何承诺，例如带宽、路由优化
- 文档里必须显著强调 Tailscale 前置条件

### 后续

- 若长期用户反馈"装 Tailscale 太重"，再评估 C 类方案（Cloudflare Tunnel）
  作为**可选插件**，但不纳入一期
- relay 侧可以额外加一条 "**只接受来自 Tailscale 接口的连接**" 的硬约束，
  作为防御深度（具体执行留给 relay 层 spec）

## 参考

- Tailscale 文档：<https://tailscale.com/kb/>
- Apple Network Extension 审核指引（说明为什么不想走这条路）
- 本项目实施计划 "NOT Building" 节：显式排除"自建公网 relay"
