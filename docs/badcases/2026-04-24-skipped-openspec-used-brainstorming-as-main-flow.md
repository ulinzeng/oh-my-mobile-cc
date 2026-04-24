---
date: 2026-04-24
tag: orchestration
session: ws-reconnect-design
related-rules: R5 (orchestration.md)
---

# Skipped OpenSpec, used brainstorming as main design flow

Session 要为项目新增"WS 断线重连 + 离线消息回放"功能。这是典型的跨 capability 架构变更，应走 OpenSpec Phase 1 proposal 流程。但 lead 直接 invoke 了 `superpowers:brainstorming` skill，把它当成主流程而非 OpenSpec 的子步骤，最终产出物去了 `docs/superpowers/specs/` 而非 `openspec/changes/<id>/`。

## 症状

| 应当 | 实际 |
|---|---|
| 识别为"新 capability / 架构变更"→ 走 Phase 1 OpenSpec proposal | 直接走 `superpowers:brainstorming` skill 的 checklist |
| 读 `openspec/AGENTS.md` + `openspec/project.md` | 没读，跳过 |
| 产出 `changes/<id>/proposal.md` + `design.md` + `tasks.md` | 产出 `docs/superpowers/specs/YYYY-MM-DD-*.md`（错误路径）|
| 跑 `openspec validate <id> --strict` | 没跑 |
| brainstorming 只作为 Phase 1 步骤 2 的需求澄清子步骤 | brainstorming 成了主流程 |

## 真实代价

- 设计产出物不在 OpenSpec 管控下，无法 `openspec show`、`openspec validate`
- spec deltas 没有写入 `openspec/specs/<cap>/spec.md`，后续实施时没有规范对齐基准
- tasks.md 没有生成，Phase 2 的 subagent dispatch 缺乏 binary progress tracking
- 用户不得不中断并指出流程错误，浪费至少 1 轮完整交互

## 根因

1. **skill 优先级判断失败**：CLAUDE.md 明确说"提到 planning / proposals / 新 capability / 架构变更时 Always open `@/openspec/AGENTS.md`"。`superpowers:brainstorming` 的触发条件（"任何创意工作之前"）与 OpenSpec Phase 1 触发条件（"跨 capability / 架构决策"）产生冲突时，项目规则应该优先。lead 没做这个优先级判断。
2. **orchestration.md Phase 1 的流程里，brainstorming 是步骤 2（可选子步骤），不是替代方案**。但 brainstorming skill 自己有一套完整的 checklist（explore → clarify → propose → present → write design doc → transition），这个 checklist 的"引力"把 lead 拉进了独立循环，脱离了 OpenSpec 主轨道。
3. **orchestration.md 禁忌清单没有明确覆盖此 pattern**：已有 7 条 anti-pattern，但都关于 plan.md / subagent / progress tracking，没有"不得绕开 OpenSpec 直接用 brainstorming / writing-plans 作为设计主流程"这一条。

## 修复措施

- **R5 规则**：新增到 orchestration.md 的刚性纠正段
- **hookify 规则**：在 invoke `superpowers:brainstorming` 之前检查是否已完成 OpenSpec proposal gate
- **本 badcase 文档**：作为复盘材料

## 自检清单（下次 session 起手加查）

- [ ] 需求涉及新 capability / 架构变更 / 跨 spec 吗？→ 是 → **必须先 `openspec:proposal`**
- [ ] `superpowers:brainstorming` 被用作什么角色？→ 只能是 Phase 1 步骤 2 的子步骤（需求澄清），不能是主流程
- [ ] 设计产出物在哪个目录？→ 必须在 `openspec/changes/<id>/`，不是 `docs/superpowers/specs/`
