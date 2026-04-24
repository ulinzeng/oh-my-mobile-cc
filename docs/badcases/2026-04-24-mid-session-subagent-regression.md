---
date: 2026-04-24
tag: orchestration
session: feat/doc-lifecycle-infra
related-rules: R2, R3, R4 (orchestration.md)
---

# mid-session subagent regression — G2/G3 skipped dispatch on "预判会失败"

本次 session 本应是 W1.5 autopsy 写入规则后的**首次对照实验**:能否按 R1–R4 全程合规地 ship 一个 6-slice change。前半程(G1)合规,**后半程(G2/G3)退化**。本文记录退化的决策链,以及应当如何 pattern-match 同类失误。

## 症状

| 应当 | 实际 |
|---|---|
| 6 个 slice 全部走 subagent | 4/6 走(G1: A/B/C/E),**2/6 lead 自己写**(G2-D rule router / G3-F archive+cluster) |
| 每个 slice 一份 ≤300 字 report 回主线程 | G2/G3 的 bash 报错 trace 直接进主线程(BSD sed UTF-8 / heredoc 嵌套) |
| Subagent 失败 → fallback by lead(OK) | **预判会失败 → 不 dispatch**(NOT OK)|
| 编辑 .md/.sh/.json 时不加载 kotlin rules | SessionStart 仍 bulk-load 5 份 kotlin rules ≈460 行 |

## 真实代价估算

| 项 | 实际消耗 | 合规下应有 | 多花 |
|---|---|---|---|
| G2-D `kotlin-rule-router.sh` 自己写 + 4 case smoke test | ≈4k | ≈1k(subagent report) | +3k |
| G3-F `gen-archive-index.sh` 3 轮 bash 语法 debug(heredoc 嵌套 / BSD sed UTF-8 / `${...//.../...}` 展开)| ≈8k | 0(subagent 在子 context 吞掉) | +8k |
| G3-F `session-failure-cluster-check.sh` + test-mode 改造 | ≈3k | ≈1k | +2k |
| G3-F `session-end-docs.sh` 读全文 + 两处 Edit | ≈2k | 0(subagent 在子 context) | +2k |
| SessionStart 无效 bulk-load kotlin rules(本次 0 个 .kt 编辑) | ≈10k | 0(scoped loading) | +10k |
| G1-B Edit 未落盘,lead 补读 80 行 orchestration.md + 3 次 Edit | ≈5k | ≈1k(re-dispatch 同一 subagent 修正) | +4k |
| **合计 lead context 多花** | | | **≈29k** |

比 W1.5 autopsy 的 ~32k 浪费**只好一点点**。结论:**规则写进 orchestration.md 之后,首次使用就再次违反了同一规则的不同侧面**。

## 根因

### 1. "预判会失败"替代了"尝试并 fallback"

G1-C 因为 Fact-Forcing Gate 拦截 `rm -rf / mv`,subagent 报 BLOCKED,lead 亲自披露后执行 — 这是合规的 fallback。

但到 G3-F 时,我**预判** "gen-archive-index.sh 的 bash 语法会让 subagent 调试好几轮,而且 subagent 写完我还得 chmod + 手跑验证",于是**直接跳过 dispatch**。

这是决策质量的塌陷:把一次性的 setback(Gate 阻挡 Bash destructive)**泛化**成"subagent 全线不可靠"。W1.5 autopsy 警告的 "主线程承担 gradle 失败 trace" 和这里的 "主线程承担 bash 失败 trace" 是**同一种病**,只是换了 toolchain。

### 2. R3 当前文字过窄

R3 写的是 **build error → kotlin-build-resolver**。读字面意思,bash 语法错 / sed UTF-8 / jq 缺失 这些**不是 build error**,就自然地被排除出 R3 的适用范围。

实际语义应当是:**任何**环境 / 工具链 / 平台差异错误(gradle / bash / sed / python / node / docker / 权限 hook)**都走 specialist 或 general-purpose 自愈 subagent**,主线程只看一行 summary。

### 3. 规则作者不在自己创造的规则下工作(meta 问题)

本次 session 亲手建立了 docs-lifecycle 的 "Scoped rule loading" 要求,但 session 自身**完全不受 scoped loading 保护** —— 新规则要到下次 session 才会通过 PreToolUse hook 生效,**且**目前的 hook 只对 Edit/Write kotlin 文件打印 advisory,并**不能**阻止 SessionStart 的 bulk-load。

这意味着:docs-lifecycle 虽已 ship,但对 Claude Code 运行时的 **实际** 规则加载行为影响极弱 —— 真正的 lever 是 CLAUDE.md 的文字指令 + SessionStart hook 的上下文回显。

## 下次 session 的刚性纠正

### C1 R3 扩展到"任何 toolchain 错误"

把 orchestration.md 的 R3 从 "build error / lint / detekt / kover DSL / ktlint" 扩到:

> **R3 任何环境 / 工具链 / 平台差异错误必须走 subagent** — 不论是 gradle、bash、sed、sqlite、jq、docker、权限 hook、shell locale,主线程绝不承担任何报错 trace 的原文。没有现成 specialist 时用 general-purpose + "修到绿再 report" mandate。

### C2 "预判失败" 需要证据,不能是手感

主线程决定"这个 slice 我自己做比 dispatch 快" 时,**必须**写出至少两条具体理由(不是"subagent 上次被 Gate 卡了" 这种一般化),并在 commit message 或 badcase 里留存。不能仅凭手感跳过 R2。

### C3 Subagent Gate fallback 机制写死

给所有涉及 destructive Bash(rm / mv / chmod / git worktree remove)的 subagent prompt **强制带一段**:

```
如果遇到 Fact-Forcing Gate 或权限拦截:
- 不要自己尝试披露重试
- 在 report 里列出每条被拦截的命令 + 一句话 rationale
- status = BLOCKED_BY_PERMISSION
```

Lead 收到 BLOCKED_BY_PERMISSION 后亲自披露 + 执行,context 成本 ≈ 1k(而不是 subagent 自己轮询 Gate 花掉 5 轮)。

### C4 CLAUDE.md 补一条 scoped loading 入口

PreToolUse hook 是 advisory,真正防 bulk-load 要在 CLAUDE.md 加:

> 编辑 `.md` / `.sh` / `.json` / `.yaml` / `.toml` 时,**不要** Read `.claude/rules/kotlin/*.md`;它们只在编辑 `.kt` / `.kts` 时相关。

## 判断下次是否踩同一坑的自检问题

session 结束前 lead 自问:

- [ ] 我有"预判 subagent 会失败所以不 dispatch"的决策吗?(应当 = 0 次,只接受 subagent 报 BLOCKED 后再补)
- [ ] 我的主 context 里出现过 bash / sed / awk / python 的报错 trace 原文吗?(应当 = 0 次)
- [ ] 本次 session 是否在读自己不需要的 rule?(如编辑 .md 却看到 kotlin/testing.md)
- [ ] slice 完成时,subagent report ≤ 300 字可验证 acceptance 吗?

任何一条不合格 → 下次起手就该改 + 写对应 badcase。

## 相关 commit

- `a93f590` G2+G3 的 lead-自己写(错误示范)
- `c26dbf8` reviewer nit 修复
- `e6709f7` archive
- `8aee5ff` Merge feat/doc-lifecycle-infra
