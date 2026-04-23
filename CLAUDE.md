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

## Workflow Orchestration (ECC × superpowers × OpenSpec)

This project uses three AI-tool layers together. Always open
`@/.claude/rules/orchestration.md` when:

- User says "make <change-id> green", "blitz <change-id>", "ship <change-id>",
  "resume", "continue" — these are orchestrator triggers
- Multiple subagents / specialists should be coordinated
- Starting a new session and need to know what to read first
- About to write `.claude/PRPs/plans/*.plan.md` or `openspec/changes/*/tasks.md`
  and need the "single source of truth" rule

Core rules enforced there:
- **OpenSpec `changes/<id>/tasks.md`** = binary progress truth (not plan.md)
- **plan.md** ≤ 300 lines, Mirrors/FileStructure/Risk only (no checklist)
- **Session handoff** = `git log` + `openspec show` + `docs/adr/`, not plan.md
- ECC skills/agents subset is pinned via `.claude/scripts/prune-ecc.sh`

The high-level "分工单一指导方针" is also in `openspec/project.md`
`## 工作流编排` for long-term reference.

## Session-start default checklist

On a fresh session, **before** touching files or asking questions:

1. `git log --oneline -10` — identify branch + latest commit
2. `openspec list` — see active changes
3. **Do NOT** Read any file > 500 lines unless it is the immediate target
   of a diff. In particular: do not Read plan.md in full; only read its
   `Mirrors` + `File Structure` + `Pointer` sections if needed.
