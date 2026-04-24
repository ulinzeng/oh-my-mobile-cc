# 工作流编排 (Workflow Orchestration) — ECC × superpowers × OpenSpec

> 本文件是项目的**执行战术手册**。规范目标 / 约束见 `openspec/project.md`。
> OpenSpec 三件套用法见 `openspec/AGENTS.md`。
> 本文件专讲三件套**怎么配合使用**、每次 session 应当进入哪个模式、触发词约定。

---

## 三件套分工

| 层 | 工具 | 输出物 | 生命周期 |
|---|---|---|---|
| **产品层** | OpenSpec | `changes/<id>/{proposal,design,tasks}.md` + `specs/<cap>/spec.md` deltas | merge 后 archive,永久 |
| **执行层** | superpowers skills | session 内的编排决策 + subagent dispatch | session |
| **Domain 知识层** | ECC skills/agents | 语言/框架/领域 know-how + specialist review agent | 被调用时 |
| **session 蓝图(可选)** | ECC `writing-plans` 或 superpowers `writing-plans` | `.claude/PRPs/plans/<id>.plan.md`(**≤300 行**) | merge 后随 change 一起 archive |

### 核心规则

1. **OpenSpec 是 binary progress 真相源**:所有 `- [ ]` / `- [x]` **只在** `openspec/changes/<id>/tasks.md`。plan.md **不再有 checklist**。
2. **plan.md 是 session 内 scratch**:只保留 ECC plan 相对 OpenSpec 的独特增量(Mirrors、File Structure、Risk、NOT-doing),总长 ≤ 300 行。
3. **跨 session 交接**:靠 `git log --oneline` + `openspec show <id> --json` + `docs/adr/` 新增的 ADR,**不靠** plan.md 里的 Session Handoff 段。
4. **规范性需求**:只写在 `openspec/specs/<cap>/spec.md` 或 change 的 spec deltas 里;plan.md / ADR / code comment 不 paraphrase spec。

---

## 四阶段工作流

### Phase 1:产品入口(OpenSpec proposal 主导)

**触发**:用户说"我想加 X"、"我要改 Y"、"设计 Z"、"propose ...",或出现跨 capability / 涉及 breaking / 涉及架构决策的需求。

**流程**:
1. Claude 读 `openspec/project.md` + `openspec/AGENTS.md`(如未读)
2. 如需求模糊:插入 `superpowers:brainstorming` 做 10-15 分钟澄清
3. `openspec:proposal` skill scaffold `changes/<id>/` 三件套 + spec deltas
4. 技术决策多时补 `design.md`
5. 跑 `openspec validate <id> --strict` → 绿
6. **Approval gate** — 用户 review → 批

**可选 ECC assist**:
- `documentation-lookup` / `exa-search` / `deep-research` — 调研第三方库
- `architecture-decision-records` — 决策重时写 ADR
- `api-design` / `hexagonal-architecture` / `mcp-server-patterns` — 领域框架

**禁忌**:
- 这阶段**不写代码**、**不改文件**(plan.md 也不写)。只产出 design artifacts。

---

### Phase 2:Session 内编排(subagent-driven-development 主导)

**触发词**(用自然语言):

| 用户说 | Lead 进入的模式 |
|---|---|
| "make `<change-id>` green" | 默认串行 orchestrator |
| "blitz `<change-id>`" | 并行 orchestrator(worktree + parallel dispatch) |
| "ship `<change-id>`" | Phase 4 合流模式(merge + archive) |
| "resume w1.5" / "continue" | 读 git log + openspec show,续上次进度 |
| "review last commit" / "review diff" | dispatch `kotlin-reviewer` + `security-reviewer` |

**Lead 标准动作**(以 "make <id> green" 为例):

1. `git log --oneline -20 <branch>` — 已完成什么(~1k tokens)
2. `openspec show <id> --json` — 未勾 tasks(~2k tokens)
3. **选择性** Read plan.md 的 **Mirrors + File Structure + Pointer** 段(不读全文,~2.5k tokens)
4. 按依赖切 **slice**(2-4 个),每 slice 对应一组连续 tasks
5. 每个 slice 跑 **3 阶段 subagent 链**:
   - **Implementer**:写代码 + 跑测试 + commit(dispatch general-purpose 或 `kotlin-build-resolver`)
   - **Spec reviewer**:对照 `openspec/specs/<cap>/spec.md` 确认符合规范(dispatch `code-reviewer`,prompt 带 spec deltas)
   - **Quality reviewer**:代码质量(dispatch `kotlin-reviewer` + 按需 `security-reviewer`)
6. 所有 review 绿 → 勾 `tasks.md` + 用户认可后推 commit

**每个 subagent 的 prompt 原则**:
- **只给它最小必要 context**(task 文本 + 相关文件路径 + 一条 MIRROR 段)
- **说清楚交付物**(代码 diff / review findings list / test 运行结果)
- **不传整份 plan.md**
- **写死限制**(不要主动改其他文件、不要跨 slice)

**可用的 specialist agent**(ECC):
- `kotlin-reviewer` — Kotlin 代码 review
- `kotlin-build-resolver` — Gradle / Kotlin 编译错误修复
- `code-reviewer` — 通用代码 review(spec 对齐用)
- `security-reviewer` — 安全审查(handshake / crypto / auth 路径必用)
- `silent-failure-hunter` — 找隐性 bug / race condition
- `refactor-cleaner` — dead code 扫描
- `doc-updater` — 更新 docs/CODEMAPS
- `harness-optimizer` — session 末审查 harness 配置
- `planner` — 需要中程规划时(避免 lead 自己吃 context)

---

### Phase 3:并行加速(可选,slice 间无依赖时)

**触发条件**:Lead 识别出 2+ 个 slice 代码层**互不依赖**(只依赖已合并的旧代码)。

**流程**:
1. `superpowers:using-git-worktrees` 为每个 slice 开 worktree(`isolation: "worktree"` 参数)
2. `superpowers:dispatching-parallel-agents` — 在**同一条 message** 下 dispatch 多个 sub-chain
3. 每条 chain 独立跑 3 阶段
4. 全绿后 lead 顺序 merge worktree → 主 branch,跑 full validation gate
5. 冲突或失败 → 用 `git worktree remove` 清掉,退回串行

**判断是否能并行**(不确定就串行):
- 两个 slice 的**文件集不相交**(或只交在 `build.gradle.kts` 的独立 `dependencies` 行)
- 两个 slice 不互相调用对方的新符号
- 两个 slice 的 test 互不依赖

**W1.5 案例**:Task 11(RelayServer) 与 Task 12(TransportPort) 文件集互不相交(前者在 `relay/server/`,后者在 `shared/core/transport/` + `shared/src/jvmMain/transport/`),**可并行**。

---

### Phase 4:合流与归档

**触发**:所有 `tasks.md` 勾完,full validation gate 绿。

**流程**:
1. `superpowers:finishing-a-development-branch` — 决定怎么合(项目惯例:`git merge --no-ff`)
2. `git checkout main && git merge --no-ff <branch>`
3. `openspec archive <change-id> --yes` — spec deltas 合并进 `specs/<cap>/spec.md`
4. plan.md(若有)随 change 一起去 `openspec/changes/archive/<date>-<id>/`(如有价值)或直接删
5. `git branch -d <branch>` 本地删,`git push origin --delete <branch>` 远端删
6. 在 `docs/adr/` 补 ADR 如果本次有遗留的技术决策(例如 W1.5 的"skew 边界 60_000ms 接受",应当变成 ADR-0006)

---

## 每次 session 起手约定

**新 session 的第一件事**(Claude 自己做,不要用户说):

1. 读 `git log --oneline -10`(确认在哪个 branch、最新 commit)
2. `openspec list`(看有哪些 active change)
3. 如果已有 active branch / change,不去读 plan.md 全文 — 只看 Mirrors+File Structure 段
4. **不要** Read 任何 >500 行的文件,除非是 diff 的目标文件
5. 跑 `/context` 或等价,确认起手 context 占用 ≤ 30%

**session 内健康指标**:
- Context 用量 < 60%:正常
- Context 用量 60-80%:用 `superpowers:strategic-compact` 主动 compact
- Context 用量 > 80%:完成当前 slice 后考虑重启 session,交接靠 commit + openspec state

---

## 禁忌(Anti-Patterns)

1. **❌ 把 plan.md 当 progress tracker** — 所有勾选只在 openspec tasks.md
2. **❌ 让 subagent 继承 lead 的完整 context** — 只传最小必要
3. **❌ 同时开多个 change 的 proposal** — 一个 branch 一个主 change(Ed25519 与 W1.5 是明确的上下游关系,不是并行)
4. **❌ 用 session handoff 附录代替 git log** — 已知 bug:w1.5 plan 曾出现两份相同的 handoff 复制粘贴
5. **❌ 跳过 `openspec validate --strict`** — Phase 1 / Phase 4 必跑
6. **❌ RED skip** — 任何生产代码变更先红后绿,commit 带 `[red]` / `[green]` / `[refactor]` tag
7. **❌ 在 plan.md 里 paraphrase spec** — spec 是规范真相,plan 只写 HOW

---

## Anti-Patterns 实录 — W1.5 session 复盘(2026-04-24)

一次完整的"反面教材":W1.5 的 `feat/w1.5-pairing-relayclient` 合流 session 里,lead 没有当编排者,而是自己下场写代码 / 跑 gradle / 修 lint,结果一个正常 4 slice 的 change 吃掉了远超预期的 context。本节留存当时的失败模式,作为下次 session 的对照组。

### 症状

| 应当 | 实际 |
|---|---|
| Lead dispatch implementer/reviewer subagent | 0 次 subagent 被 dispatch,全部代码在主线程手写 |
| Lead 读 plan.md 的 Mirrors+File Structure 两段(≈2.5k) | 没读 plan.md,但 Read 了整份 `WireMessage.kt`(~320 行 ≈4k) |
| Build error / lint 失败 由 `kotlin-build-resolver` 处理 | 12 次 gradle 失败全部 trace 进主线程(≈20k) |
| Section 11 与 Section 12 文件集互不相交 → 并行 worktree dispatch | 全串行,4 个 section 一个接一个 |

### 真实代价估算

| 项 | 实际消耗 | 合规下应有 | 多花 |
|---|---|---|---|
| gradle 失败 trace 读进主线程 | ≈20k | 0(resolver 在子 context 吞掉) | +20k |
| 全量 WireMessage.kt | ≈4k | 0(planner 切分阶段发现 dead 分支,reviewer 建议去重) | +4k |
| FakeRelay / KtorRelayClient 反复校核读 | ≈8k | 0(在 implementer 子 context) | +8k |
| **合计 lead context 多花** | | | **≈32k** |

结论:**本可以用现有 1/8 的 context 完成同样的工作**。

### 根因

1. **把"切 slice"当成 lead 的动作,而不是 planner agent 的动作**。切分本身要读 tasks.md + spec delta,~5k 输入,应当在 planner 子 context 做,只回一份 <300 字 slice 清单。
2. **把"让 green"当成 lead 的动作**。每个 slice 的 implementer 应当在自己的子 context 里承担 (a) 读 Mirror + 最小相关源 (b) 写代码 (c) 跑 gradle 直到全绿 (d) commit (e) 回报 hash + 测试计数一行 summary。主线程**不 Read 源代码,不看 gradle trace**。
3. **build error 战争走主线程**。`kotlin-build-resolver` 专门为此存在,每次 lint/detekt/kover DSL 不兼容/ktlint import order/ReturnCount...都是它的业务。主线程不应出现 `detekt.txt` / `ktlintCheck.txt` 的原文。

### 下次 session 的刚性纠正

**R1 起手即 dispatch planner** — session 起手(读完 `git log -10` + `openspec list` 之后)立刻:

```
Agent(subagent_type="planner",
      prompt="读 openspec/changes/<active-id>/tasks.md + spec deltas,
              切成 2-4 个 slice。每个 slice 给:一句话目标 + 允许读/写文件清单 + 依赖前置。
              <300 字返回。")
```

**R2 每个 slice 的 Implementer 必须走 subagent** — 主线程永不 Read >200 行源代码,永不读 gradle trace 原文。Implementer prompt 模板:

```
Agent(subagent_type="general-purpose",
      prompt="<slice N 目标:一句话>
              - 允许读: <具体文件清单>
              - 允许写: <具体文件清单>
              - 成功标志: <test 类列表全绿 + ktlintCheck + detekt + 相关 kover gate>
              - 失败自愈: build/lint/detekt 失败自己修,不要回报主线程
              - 输出物: commit hash + 测试计数 + 一行 diff summary
              - 禁止: 读 plan.md 全文; 改其他 slice 的文件")
```

**R3 build error 战走 `kotlin-build-resolver`** — 任何 lint/detekt/koverDSL/编译错误,lead 的动作只有一条:

```
Agent(subagent_type="everything-claude-code:kotlin-build-resolver",
      prompt="gradle task <task-name> 当前红;修到绿,最小改动。")
```

**R4 文件集不相交的 slice 必须并行** — 一条 message 里多个 `Agent` tool call + `isolation: "worktree"`。Lead 等所有 return 后顺序 merge worktree。W1.5 的 Section 11 (RelayServer) vs Section 12 (TransportPort) 就是典型可并行对,本次错过。

### 判断下次是否踩同一坑的自检问题

session 结束前 lead 自问:

- [ ] 我 Read 过 >300 行的源代码吗?(应当 = 0 次,除非是 diff 的直接目标)
- [ ] 我的主 context 里出现过 `BUILD FAILED` 的 stack trace 吗?(应当 = 0 次)
- [ ] 我 dispatch 了几个 subagent?(应当 ≥ 切分的 slice 数)
- [ ] 有并行机会时我用了 worktree 吗?

四条任何一条不合格 → session 下次起手就该改。

---

## ECC skill/agent 精简约定

- 保留清单定在 `.claude/scripts/prune-ecc.sh`(~34 skill + ~10 agent)
- ECC 升级后执行 `bash .claude/scripts/prune-ecc.sh` 重新 apply prune
- 需要一个暂时没保留的 skill/agent 时:
  - 临时:`mv .../skills-disabled/<name> .../skills/`(session 内生效需重启)
  - 永久:在 `prune-ecc.sh` 的 `KEEP_*` 数组加上,重跑

---

## 参考

- `openspec/AGENTS.md` — OpenSpec 三件套 CLI 用法
- `openspec/project.md` — 项目规范/约束真相源
- `.claude/rules/kotlin/` — Kotlin 编码标准
- `superpowers:using-superpowers` — skills 启动原则
- `superpowers:subagent-driven-development` — subagent 编排模型
- `.claude/scripts/prune-ecc.sh` — ECC 精简脚本
