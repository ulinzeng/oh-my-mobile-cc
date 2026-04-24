---
date: 2026-04-24
tag: orchestration
session: w1.5-pairing-relayclient
related-rules: R1, R2, R3, R4 (orchestration.md)
---

# W1.5: Lead did not dispatch subagents

一次完整的"反面教材":W1.5 的 `feat/w1.5-pairing-relayclient` 合流 session 里,lead 没有当编排者,而是自己下场写代码 / 跑 gradle / 修 lint,结果一个正常 4 slice 的 change 吃掉了远超预期的 context。本节留存当时的失败模式,作为下次 session 的对照组。

## 症状

| 应当 | 实际 |
|---|---|
| Lead dispatch implementer/reviewer subagent | 0 次 subagent 被 dispatch,全部代码在主线程手写 |
| Lead 读 plan.md 的 Mirrors+File Structure 两段(≈2.5k) | 没读 plan.md,但 Read 了整份 `WireMessage.kt`(~320 行 ≈4k) |
| Build error / lint 失败 由 `kotlin-build-resolver` 处理 | 12 次 gradle 失败全部 trace 进主线程(≈20k) |
| Section 11 与 Section 12 文件集互不相交 → 并行 worktree dispatch | 全串行,4 个 section 一个接一个 |

## 真实代价估算

| 项 | 实际消耗 | 合规下应有 | 多花 |
|---|---|---|---|
| gradle 失败 trace 读进主线程 | ≈20k | 0(resolver 在子 context 吞掉) | +20k |
| 全量 WireMessage.kt | ≈4k | 0(planner 切分阶段发现 dead 分支,reviewer 建议去重) | +4k |
| FakeRelay / KtorRelayClient 反复校核读 | ≈8k | 0(在 implementer 子 context) | +8k |
| **合计 lead context 多花** | | | **≈32k** |

结论:**本可以用现有 1/8 的 context 完成同样的工作**。

## 根因

1. **把"切 slice"当成 lead 的动作,而不是 planner agent 的动作**。切分本身要读 tasks.md + spec delta,~5k 输入,应当在 planner 子 context 做,只回一份 <300 字 slice 清单。
2. **把"让 green"当成 lead 的动作**。每个 slice 的 implementer 应当在自己的子 context 里承担 (a) 读 Mirror + 最小相关源 (b) 写代码 (c) 跑 gradle 直到全绿 (d) commit (e) 回报 hash + 测试计数一行 summary。主线程**不 Read 源代码,不看 gradle trace**。
3. **build error 战争走主线程**。`kotlin-build-resolver` 专门为此存在,每次 lint/detekt/kover DSL 不兼容/ktlint import order/ReturnCount...都是它的业务。主线程不应出现 `detekt.txt` / `ktlintCheck.txt` 的原文。

## 判断下次是否踩同一坑的自检问题

session 结束前 lead 自问:

- [ ] 我 Read 过 >300 行的源代码吗?(应当 = 0 次,除非是 diff 的直接目标)
- [ ] 我的主 context 里出现过 `BUILD FAILED` 的 stack trace 吗?(应当 = 0 次)
- [ ] 我 dispatch 了几个 subagent?(应当 ≥ 切分的 slice 数)
- [ ] 有并行机会时我用了 worktree 吗?

四条任何一条不合格 → session 下次起手就该改。
