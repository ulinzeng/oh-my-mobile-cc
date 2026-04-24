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
3. **R5 gate**:用户需求涉及新 capability / 架构变更 / 跨 spec 吗?→ 是 → **必须先走 `openspec:proposal`**,brainstorming 只能做子步骤
4. 如果已有 active branch / change,不去读 plan.md 全文 — 只看 Mirrors+File Structure 段
5. **不要** Read 任何 >500 行的文件,除非是 diff 的目标文件
6. 跑 `/context` 或等价,确认起手 context 占用 ≤ 30%

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
8. **❌ 用 brainstorming / writing-plans 绕开 OpenSpec** — 新 capability / 架构变更 / 跨 spec 的需求 **必须** 先走 `openspec:proposal`。`superpowers:brainstorming` 只能作为 Phase 1 步骤 2 的**需求澄清子步骤**,其产出物喂给 proposal,而非替代 proposal。设计文档只能在 `openspec/changes/<id>/` 下,不能在 `docs/superpowers/specs/`。

---

## Anti-Patterns 实录

### W1.5 session 复盘(2026-04-24)

> 完整 autopsy 见 `docs/badcases/2026-04-24-w15-lead-did-not-dispatch-subagents.md`。
> 本节仅保留 **R1–R4 规范性纠正**。

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

### WS-reconnect session 复盘(2026-04-24)

> 完整 autopsy 见 `docs/badcases/2026-04-24-skipped-openspec-used-brainstorming-as-main-flow.md`。

**R5 新 capability / 架构变更必须先过 OpenSpec gate** — session 中识别到需求涉及新 capability、跨 spec、架构决策时,lead 的**第一个动作**:

```
1. Read openspec/AGENTS.md (如未读)
2. Read openspec/project.md (如未读)
3. 如需求模糊 → invoke superpowers:brainstorming 做澄清(≤15min),
   产出喂给下一步,不独立成文
4. invoke openspec:proposal → scaffold changes/<id>/ 三件套
5. openspec validate <id> --strict → 绿
6. 用户审批
```

**判断标准**（任一命中 → 必须走 OpenSpec）:
- 需要新增 / 修改 `openspec/specs/<cap>/spec.md`
- 涉及 WireMessage 新增 op / 字段变更
- 引入新的模块 / 新的端口接口
- 跨两个以上已有 capability 的交互变更

**禁止**:
- ❌ 把 `superpowers:brainstorming` 当主流程,产出到 `docs/superpowers/specs/`
- ❌ 把 `superpowers:writing-plans` 当设计替代,跳过 proposal + spec deltas
- ❌ 在没有 active `openspec/changes/<id>/` 的情况下进入 Phase 2 实施

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
