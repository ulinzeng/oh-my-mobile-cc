<!-- OPENSPEC:START -->
# OpenSpec Instructions

These instructions are for AI assistants working in this project.

Always open `@/openspec/AGENTS.md` when the request:
- Mentions planning or proposals (words like proposal, spec, change, plan)
- Introduces new capabilities, breaking changes, architecture shifts, or big performance/security work
- Sounds ambiguous and you need the authoritative spec before coding

Use `@/openspec/AGENTS.md` to learn:
- How to create and apply change proposals
- Spec format and conventions
- Project structure and guidelines

Keep this managed block so 'openspec update' can refresh the instructions.

<!-- OPENSPEC:END -->

---

# Agent 工作手册（oh-my-mobile-cc）

> 本文是 **AI agent 的入口**。人类读者请先看 `README.md` / `README.zh-CN.md`。
> 上方 `OPENSPEC:START/END` 块由 `openspec` CLI 管理，**请勿手动编辑**。

## 1. 项目导航地图

| 角色 | 位置 | 何时打开 |
| --- | --- | --- |
| 真相源 | `openspec/project.md`、`openspec/specs/**` | 每次接到任务，**先读** |
| 当前冲刺 | `openspec/changes/<id>/tasks.md` | 任务描述提到某 change id 时 |
| 实施计划 | `.claude/PRPs/plans/kmp-claude-code-remote.plan.md` | 需要宏观上下文、阶段划分、风险时 |
| 架构决策 | `docs/adr/0001..0003.md` | 回答"为什么是 KMP / Tailscale / Inbox"时 |
| 渐进技能 | `docs/skills/*.md` | 文件头 `triggers` 命中关键字时按需加载 |
| 编码规则 | `.claude/rules/kotlin/`（后续会建） | 写 Kotlin 代码前 |
| 自动文档 | `docs/CODEMAPS/*.md`、`CHANGELOG.md` | **只读**，由 SessionEnd hook 生成 |

**加载原则**：不要一次读完所有文档。先 `project.md` + `tasks.md`，再根据关键字
匹配 `docs/skills/*.md` 的 `triggers` YAML 字段按需拉取。

## 2. Agent 工作流

接到任何任务，按下列顺序：

1. **读上下文**：`openspec/project.md` → 当前 `openspec/changes/<active>/tasks.md`（若有）
2. **匹配 skill**：扫描 `docs/skills/*.md` 的 frontmatter，命中即加载
3. **判断是否改需求**：
   - **改需求** → 先在 `openspec/changes/<new-id>/` 建 `proposal.md` + `design.md` + `tasks.md` + `specs/<capability>/spec.md`，`openspec validate --strict` 绿后再动代码
   - **不改需求**（修 bug、加测试、补文档）→ 直接走 TDD
4. **TDD**：先 `[red]` commit，后 `[green]` commit（见下文）
5. **收尾**：`/exit` 触发 SessionEnd hook，自动追写 CODEMAPS 与 CHANGELOG

## 3. TDD 纪律（强制）

引用 plan 的 `Red → Green → Refactor` 规则：

- **任何** 生产代码变更必须**先**提一个 failing test commit（`[red]`）
- 再提一个让测试通过的 commit（`[green]`）
- 可选的 `[refactor]` commit 必须保持全部测试绿
- PR squash 前**必须**保留 `[red]` / `[green]` 历史，CI 会检查
- 测试金字塔目标占比：Unit 65% / Contract 20% / Integration 10% / E2E 5%
- 覆盖率门槛：`shared/core ≥ 90%`、`shared/features ≥ 80%`、`relay ≥ 70%`；
  `ui/**` 豁免

CI 门禁命令：

```bash
./gradlew :shared:allTests :relay:test
./gradlew koverHtmlReport koverVerify
openspec validate --strict
```

## 4. 并行 Agent 协议

plan 里的图例：🅟 = 可并行热点；🅢 = 强制串行。

规则：

- **🅟 并行前，主 Claude（总协调 agent）先定稿接口/sealed 基类**，子 agent 才能
  按接口分头落地实现
- 子 agent 产物必须能独立编译 + 独立跑 unit test
- **🅢 串行** 的任务不得并行（例如协议 sealed 基类、ApprovalBridge、Pairing）
- 并行完成后由主 Claude 做一次 integration run，冲突则重新定稿接口再派单

典型并行点：`CCEvent` vs `WireMessage` 的 GREEN 阶段、ANSI parser vs Grid model、
Maestro vs XCUITest E2E。

## 5. 提交信息规范（Conventional Commits）

强制使用下列前缀：

```
feat:      新增能力
fix:       修 bug
docs:      纯文档
test:      只改测试（含 [red]/[green] 标签）
refactor:  不改行为的重构
chore:     构建 / 配置
perf:      性能
```

TDD commit 示例：

```
test: [red] ApprovalInteractor times out after 10 min
feat: [green] implement Clock-based expiry in ApprovalInteractor
refactor: extract ApprovalExpiryPolicy
```

PR 标题推荐格式：`<type>(<scope>): <summary>`，例如
`feat(approval): add Inbox timeout policy`。

## 6. 禁止事项清单

下面任何一条都会导致 PR 被拒：

- ❌ 硬编码 token / IP / Tailnet 名（必须来自 `SecureStoragePort` 或运行时配置）
- ❌ 引入公网依赖的测试（integration / E2E 除外，且必须有 opt-in flag）
- ❌ `@Ignore` 超过 7 天 —— 修掉或删掉
- ❌ 无测试的 commit（docs-only 例外）
- ❌ `core/**` 引入任何框架依赖（Ktor / Compose / pty4j / SqlDelight 一律禁）
- ❌ `GlobalScope`、`throw Exception(...)` —— 用 `RemoteError` sealed 层次
- ❌ 直接修改 `openspec/project.md` 的 `## Tech Stack` / `## Architecture Patterns`
  —— 必须走 `openspec/changes/<id>/`
- ❌ 越过 `openspec/specs/**` 写代码 —— 没有 spec 的需求不存在
- ❌ 在 `README.md` 或英文代码注释里夹中文 —— 语言规范见 `project.md`

## 7. SessionEnd Hook

Claude Code 在 `/exit` 时会触发 `.claude/scripts/session-end-docs.sh`，该脚本负责：

1. `openspec validate --strict` —— 失败仅告警，不阻断
2. 扫描 `openspec/changes/*/tasks.md` —— 列出可归档的 change
3. `gen-codemaps.sh` —— 重新产出 `docs/CODEMAPS/*.md`
4. `git diff --stat` + Conventional Commits 汇总 —— append 到 `CHANGELOG.md`
5. 所有写入**先 diff 打印**；`PENDING_DOCS_ONLY=1` 时改写到 `.claude/pending-docs/`
   供人工复核

**agent 约束**：
- 不要在会话中手动重复调用该脚本
- 不要把 `docs/CODEMAPS/**`、`CHANGELOG.md` 当作真相源编辑 —— 它们是生成物
- 如果 hook 报错，先修 `openspec validate`，再考虑改脚本

## 8. 快速速查

| 我想… | 去哪里 |
| --- | --- |
| 改协议字段 | `openspec/changes/<new-id>/specs/protocol/spec.md` |
| 加新 feature 的 UI | `shared/src/commonMain/.../ui/<feature>/` + `features/<feature>/` |
| 接新的桌面能力 | `relay/src/main/kotlin/.../<new-bridge>/` |
| 写 ANSI parser 测试 | 先加载 `docs/skills/ansi-parser-deep-dive.md` |
| 调 stream-json fixture | `shared/src/commonTest/resources/fixtures/` + 看 plan W0.6 节 |
| 回答"为什么不上 APNs" | `docs/adr/0003-inbox-vs-apns.md` |

---

有歧义时，**以 `openspec/specs/**` 为准**；其次是 `openspec/project.md`；
再其次是 plan 文件；最后才是本 AGENTS.md。
