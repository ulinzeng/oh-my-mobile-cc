---
name: openspec-workflow
description: 说明本项目的 OpenSpec 驱动工作流（proposal → spec → task → test → archive）
triggers: ["openspec", "spec-driven", "change proposal", "openspec validate", "openspec archive", "spec"]
related-specs: [openspec/AGENTS.md]
---

# OpenSpec 工作流

本 skill 说明 `oh-my-mobile-cc` 项目如何用 OpenSpec 做"需求可追溯"的规格驱动开发。
任何涉及规格新增、修改、验证、归档的任务都应先加载本 skill。

本机已安装 `@fission-ai/openspec` **1.3.1**（见 `openspec/project.md` 技术栈章节），
无需额外 bootstrap。

> **适用场景**：capability 新增/调整、spec 条款修改、task 状态流转、archive 决策。
> **不适用场景**：纯代码重构、bug 修复、注释 typo、格式化（见"小改免 proposal"一节）。

---

## 1. 核心理念

> "规格是真相源，代码是规格的投影。"

- **specs/** 里的 `.md` 是**不变真相**（当前系统应有的行为）。
- **changes/** 里的是**提议的变更**（尚未落地 / 正在落地）。
- 任何新能力先开 proposal，再改 spec，再动代码，再补 test，最后 archive。
- tasks 未全 `[x]` 不允许 archive。

闭环图示：

```
requirement
    │
    ▼
openspec/changes/<id>/proposal.md
    │
    ▼
openspec/changes/<id>/specs/<cap>/spec.md  ──► 合入 openspec/specs/<cap>/spec.md
    │                                                    │
    ▼                                                    │
openspec/changes/<id>/tasks.md  ──►  code + tests        │
    │                                                    │
    └──────────────► openspec archive <id>  ─────────────┘
```

核心原则：**任何未写入 spec 的需求都不存在**。代码由 spec 推导，而不是反向补文档。

---

## 2. 何时开 proposal，何时可跳过（"小改免 proposal"）

### 必须开 proposal（change 流程）

- 新增/删除 capability（本项目目前 5 个：`protocol` / `approval-inbox` / `terminal` / `file-sync` / `pairing`）。
- 现有 spec 的**行为**发生变化（例如审批超时从 10 分钟改 5 分钟）。
- 接口契约变化（`WireMessage`、`CCEvent` 的字段增删）。
- 引入新的外部依赖或 ADR。

### 小改免 proposal（直接 PR）

- 文案、错别字、排版、翻译。
- 代码注释、KDoc。
- 测试加固（不改断言语义）。
- 内部重构、变量改名、文件移动（行为不变）。
- CI 脚本微调、Gradle 版本号小升。

判断口诀：**是否改变了外部可观察契约？改 → 必 proposal；不改 → 免。**

---

## 3. CLI 速查表

本项目只用官方 CLI，不手搓 `openspec/` 目录。

```bash
# 初始化（仅项目首日执行一次；已在 Phase W0 Task 0.1 完成）
openspec init

# 列出全部 spec 与 active change
openspec list

# 严格校验（CI gate；失败即非零退出）
openspec validate --strict

# 只校验某个 change
openspec validate <change-id> --strict

# 查看 spec / change 渲染结果
openspec show <id>

# 归档已完成 change（tasks 全部 [x] 且 validate 通过）
openspec archive <change-id>
```

本地 PR 前自检组合：

```bash
openspec validate --strict && ./gradlew :shared:allTests :relay:test
```

CI 里只使用：

```bash
openspec list
openspec validate --strict
```

---

## 4. 本项目的 5 个 spec capability

与 `openspec/specs/` 目录一一对应（来源：plan v2 "Documentation System" 节）：

| capability       | 路径                                     | 关注点                                        |
| ---------------- | ---------------------------------------- | --------------------------------------------- |
| `protocol`       | `openspec/specs/protocol/spec.md`        | `CCEvent` / `WireMessage` / NDJSON 编解码契约 |
| `approval-inbox` | `openspec/specs/approval-inbox/spec.md`  | 移动端审批 Inbox、10 分钟超时、policy 表      |
| `terminal`       | `openspec/specs/terminal/spec.md`        | VT100/ANSI 状态机 + Compose/SwiftUI 自绘      |
| `file-sync`      | `openspec/specs/file-sync/spec.md`       | 文件增量同步（Myers diff）                    |
| `pairing`        | `openspec/specs/pairing/spec.md`         | 6 位配对码 + Ed25519 + Tailscale 前提         |

新增 capability 前必须先确认上述 5 个不能覆盖该需求，避免 capability 膨胀。
每个 proposal 的 `## Impact` 段必须显式列出它影响的 capability。

---

## 5. proposal 骨架

新建一个 change 时按下面目录布置：

```
openspec/changes/<change-id>/
├── proposal.md
├── design.md          # 可选；复杂设计才写
├── tasks.md
└── specs/
    └── <capability>/
        └── spec.md    # 只写"本次变更后的 spec 片段"（ADDED/MODIFIED/REMOVED）
```

`proposal.md` 最小骨架：

```markdown
# <change-id>

## Why
<一句话：问题背景 + 触发事件>

## What Changes
- 新增 capability <name>（或 扩展 <cap>：ADDED/MODIFIED/REMOVED 哪些条款）
- 涉及 WireMessage / CCEvent 的字段变化（如有）
- 涉及端口或 adapter 的接口变更（如有）

## Impact
- Affected specs: protocol, approval-inbox
- Affected code: relay/approval/**, shared/features/approval/**
- Migration: <若有老数据/老字段兼容策略>
```

不要在 proposal 里粘贴整份新 spec，只写 **delta**；完整 spec 放
`changes/<id>/specs/<cap>/spec.md`，archive 时合并回 `openspec/specs/<cap>/spec.md`。

---

## 6. `tasks.md` 的 `[x] / [ ]` 约定

`tasks.md` 是 archive 的唯一准入门（闭环检测）。格式：

```markdown
# Tasks — add-approval-inbox

## W0 – Bootstrap
- [x] 0.1 openspec init 已执行
- [x] 0.2 proposal 通过 validate --strict

## W1 – Protocol + Approval
- [x] 1.1 [RED]    CCEventTest round-trip + unknown fallback
- [x] 1.2 [GREEN]  CCEvent 实现（sealed base）
- [x] 1.4 [RED]    ApprovalBridgeTest 以 fixture 驱动
- [x] 1.4 [GREEN]  ApprovalBridge 事件识别 + stdin 回写
- [ ] 2.3          SqlDelight approvals 表 migration
- [ ] 2.3          InboxScreen 列表倒序 + 角标
```

规则：

1. 每条 task 必须可独立验证（`VALIDATE` 手段明确），方括号严格一个空格。
2. TDD 阶段在标题里显式标注 `[RED]` / `[GREEN]` / `[REFACTOR]`；RED 必须在对应 GREEN 之前勾。
3. 跨 agent 并行条目用 `🅟`，串行用 `🅢`（与 plan 同符号）。
4. 只要有一条是 `[ ]`，`openspec archive` **必须拒绝**。
5. 任务放弃不要直接删除，改成：

   ```markdown
   - [x] ~~W3.2 xterm.js 降级实现~~ (dropped: 自绘达标，见 ADR-0004)
   ```

---

## 7. SessionEnd hook 如何提示归档候选

`.claude/settings.local.json` 已注册 SessionEnd hook，调 `.claude/scripts/session-end-docs.sh`。
该脚本在会话结束时遍历 `openspec/changes/*/tasks.md`：

```bash
#!/usr/bin/env bash
set -euo pipefail

# 1. 非阻断式 validate
openspec validate --strict || echo "WARN: openspec validate failed (non-blocking)"

# 2. 扫描 ready-to-archive 候选
for dir in openspec/changes/*/; do
    id="$(basename "$dir")"
    tasks="$dir/tasks.md"
    [ -f "$tasks" ] || continue
    if ! grep -q '^- \[ \]' "$tasks"; then
        echo "READY-TO-ARCHIVE: $id  (run: openspec archive $id)"
    else
        pending=$(grep -c '^- \[ \]' "$tasks" || true)
        echo "pending($pending): $id"
    fi
done

# 3. 另外：gen-codemaps.sh + CHANGELOG 追写（此处略）
```

输出示例：

```
READY-TO-ARCHIVE: add-approval-inbox  (run: openspec archive add-approval-inbox)
pending(3): bootstrap
```

看到 `READY-TO-ARCHIVE` 之后，下次主动会话里执行：

```bash
openspec validate <id> --strict && openspec archive <id>
```

归档成功后 `openspec list` 的 active change 列表不再含该 id，delta 会合入 `openspec/specs/`。
hook 采用**非阻断**策略：验证失败仅警告，不中断 `/exit`。

---

## 8. 与 TDD gate 的衔接

OpenSpec 工作流与 TDD 是嵌套关系：

```
proposal → tasks.md 列出 [RED] / [GREEN] / [REFACTOR] 条目
           │
           ▼
  每个 [RED] 条目 = 一次 failing test commit
  每个 [GREEN] 条目 = 使上条 RED 变绿的最小实现 commit
           │
           ▼
  全部 [x] 后，openspec validate --strict 通过
           │
           ▼
  openspec archive <id>
```

因此 proposal 阶段就应把 RED / GREEN 拆到足够小，**不允许**一个 `[x]` 对应跨模块大提交。

---

## 9. 参考事实（来自 `openspec/project.md`）

- 工具链：Kotlin Multiplatform + Compose Multiplatform + Ktor + SqlDelight + Kotest + Kover
- 依赖版本由 `gradle/libs.versions.toml` 集中管理
- relay 以 JVM 运行，使用 `claude -p --output-format stream-json --input-format stream-json`
- 语言规范：spec / AGENTS / skills 用中文；代码注释用英文；README 英文

上述事实由 `project.md` 最终仲裁；若本文件与 `project.md` 冲突，以 `project.md` 为准。

---

## 10. 常见陷阱

| 症状                                   | 根因                                   | 修复                                      |
| -------------------------------------- | -------------------------------------- | ----------------------------------------- |
| `openspec validate` 报 missing header  | spec 节缺少 ADDED/MODIFIED/REMOVED     | 至少补齐一个章节                          |
| archive 后 spec 未合并到 `specs/`      | `tasks.md` 未全部 `[x]`                | 补全 checkbox 或拆分 change               |
| 同 capability 两个 change 并行冲突     | 未提前约定接口                         | 串行化或主 Claude 先定稿接口再 `🅟`        |
| 文档语言混用                           | skill/spec 误用英文                    | 仅 README、代码注释用英文；其余中文       |
| 手改 `openspec/specs/**` 不走 change   | delta 与 spec 对不上                   | 回滚手改，重新开 proposal                 |
| proposal 改名                          | 目录名即 id，提 PR 后改名造成引用失效  | archive + 开新 proposal                   |

---

## 11. 快速自检清单

- [ ] 我的变更是否改变外部可观察契约？若是 → 已建 proposal
- [ ] `proposal.md` 三节（Why / What Changes / Impact）是否齐全？
- [ ] 增量 spec 是否标注了 ADDED / MODIFIED / REMOVED？
- [ ] `tasks.md` 每条是否可独立 validate，且 `[RED]` 在 `[GREEN]` 之前？
- [ ] `openspec validate --strict` 是否通过？
- [ ] 所有 task 打勾后是否运行 `openspec archive <id>`？

---

## 12. 相关材料

- `openspec/AGENTS.md`：OpenSpec 官方 AGENTS 模板定制版，给 agent 看的最短指令集。
- `openspec/project.md`：本项目技术栈快照。
- 官方仓库：<https://github.com/Fission-AI/OpenSpec>（1.3.1）。
- Plan：`.claude/PRPs/plans/kmp-claude-code-remote.plan.md` 的 "Documentation System (OpenSpec-driven)" 章节。
