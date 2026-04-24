# Change Proposal: add-doc-lifecycle-infra

## Why

The current documentation system has four structural problems that make it
impossible for AI assistants (or humans) to do **progressive, task-scoped
loading** of the project's accumulated knowledge:

1. **No index for ADRs.** `docs/adr/` has six files (0001–0006) with no
   INDEX and no YAML front-matter. An agent that wants "the ADR about
   Ed25519" has to either Read every file or guess from filenames. This
   defeats the purpose of an ADR corpus.

2. **No `docs/badcases/` directory.** The W1.5 session autopsy
   (lead-did-not-dispatch-subagents) currently lives embedded in
   `.claude/rules/orchestration.md` as a 70-line section. Future autopsies
   will either keep bloating that file or get lost in commit messages.
   Bad cases are a durable learning artifact and deserve their own tree
   with a known path.

3. **Bulk-loaded rule layers.** `.claude/rules/kotlin/*` is loaded
   wholesale any time a `.kt` file is touched (all 5 files, ~460 lines),
   even for tasks that do not need `testing.md` or `security.md`. No
   mechanism exists for "load this subset based on the file path being
   edited."

4. **Stale / dangerous scratch paths.**
   - `.claude/pending-docs/` has been dead since 2026-04-22 but still
     exists as a misleading entry point.
   - `.claude/PRPs/plans/kmp-claude-code-remote.plan.md` is 628 lines and
     violates the ≤300 line rule from `orchestration.md`.
   - `docs/skills/` has 4 files (1396 lines total) with no INDEX.
   - `openspec/changes/archive/` has 4 archived changes with no INDEX,
     making it expensive to audit what has shipped.

## What Changes

This is a **docs-only / tooling-only** change — no capability spec deltas,
no runtime behavior change. All work is in `docs/`, `.claude/`, and
`openspec/changes/archive/` indexing.

- **P0.1** Add `docs/adr/INDEX.md` + YAML front-matter to all six ADR files.
- **P0.2** Create `docs/badcases/` with an INDEX; extract the W1.5 autopsy
  section from `.claude/rules/orchestration.md` into
  `docs/badcases/2026-04-24-w15-lead-did-not-dispatch-subagents.md`,
  replacing the in-file section with a single-line pointer.
- **P1.1** Delete `.claude/pending-docs/` (dead since 2026-04-22).
- **P1.2** Decide and act on
  `.claude/PRPs/plans/kmp-claude-code-remote.plan.md` (either archive into
  `openspec/changes/archive/…/plan.md` if it maps to a shipped change, or
  delete if superseded).
- **P2.1** Introduce directory-scoped rule loading in `.claude/settings.json`
  so that editing a file in (e.g.) `shared/src/*` loads
  `.claude/rules/kotlin/coding-style.md` + `patterns.md` but does NOT
  bulk-load `testing.md` / `security.md` / `hooks.md` unless the file
  path or task hints require them.
- **P2.2** Add `docs/skills/INDEX.md` + YAML front-matter on all four
  skill docs (`when-to-load`, `covers`, `size`).
- **P3.1** Add `.claude/scripts/gen-archive-index.sh` that regenerates
  `openspec/changes/archive/INDEX.md` from directory listing (shipped
  change → date → one-line summary). Make it idempotent + safe to run
  in a Git hook.
- **P3.2** Add a SessionEnd hook entry in `.claude/settings.json` that
  prints a "failure-cluster hint" if the session's git log contains
  ≥3 commits tagged `[red]` without a following `[green]` — signals
  a stuck TDD cycle that should probably spawn a badcase writeup.

## Impact

**Affected files:** `docs/adr/*`, `docs/badcases/**` (new), `docs/skills/*`,
`.claude/settings.json`, `.claude/scripts/gen-archive-index.sh` (new),
`.claude/rules/orchestration.md`, `.claude/pending-docs/` (deleted),
`.claude/PRPs/plans/kmp-claude-code-remote.plan.md` (archived or deleted),
`openspec/changes/archive/INDEX.md` (new).

**Affected specs:** **None.** This change intentionally does NOT introduce
capability spec deltas — the project's `openspec/specs/` tree describes
runtime contracts (pairing, protocol) and is unaffected. The
documentation system is project-meta, not a shipped capability.

**Risk:** Low. No production code, no tests, no build config changes.
Biggest risk is `.claude/settings.json` being touched by two slices (D
and F) — mitigated by serializing D before F (see `tasks.md`). Second
risk is the W1.5 autopsy line-range in `orchestration.md` drifting if
anyone edits the file before Slice B runs — mitigated by pinning on
the heading `## Anti-Patterns 实录 — W1.5 session 复盘` rather than
line numbers.

**NOT doing (out of scope):**
- Rewriting existing `CHANGELOG.md`.
- Auto-generated `docs/CODEMAPS/*` (would need a separate change).
- Modifying `openspec/specs/*` or `openspec/project.md`.
- ECC skill/agent prune list changes.
