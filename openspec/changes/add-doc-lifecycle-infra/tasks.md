# Tasks: add-doc-lifecycle-infra

Six execution slices grouped into three dispatch waves. Slices within
the same group are file-disjoint and can run in parallel worktrees per
`.claude/rules/orchestration.md` R4.

| Slice | Group | Agent kind         | Files                                                         |
|-------|-------|--------------------|---------------------------------------------------------------|
| A     | G1    | doc-updater        | `docs/adr/INDEX.md` (new) + YAML front-matter on 000{1..6}*.md |
| B     | G1    | doc-updater        | `docs/badcases/**` (new) + edit `.claude/rules/orchestration.md` |
| C     | G1    | general-purpose    | delete `.claude/pending-docs/`, act on `kmp-...plan.md`       |
| E     | G1    | doc-updater        | `docs/skills/INDEX.md` (new) + front-matter on 4 skill docs    |
| D     | G2    | general-purpose    | `.claude/settings.json` (rule-dir routing)                    |
| F     | G3    | general-purpose    | `.claude/scripts/gen-archive-index.sh` + `.claude/settings.json` SessionEnd hook |

---

## P0 — High-Impact Indexing (G1)

### Slice A: ADR INDEX + front-matter
- [x] P0.1.1 Create `docs/adr/INDEX.md` listing 0001–0006 with one-line summaries.
- [x] P0.1.2 Add YAML front-matter (`status`, `date`, `depends-on`, `superseded-by?`) to each of `0001-...md` through `0006-...md`.
- [x] P0.1.3 Verify `docs/adr/INDEX.md` renders the six entries correctly.

### Slice B: badcases directory + W1.5 autopsy extraction
- [x] P0.2.1 Create `docs/badcases/INDEX.md` with column schema (date, slug, tag, lesson).
- [x] P0.2.2 Create `docs/badcases/2026-04-24-w15-lead-did-not-dispatch-subagents.md` by relocating the "Anti-Patterns 实录 — W1.5 session 复盘" section from `.claude/rules/orchestration.md`.
- [x] P0.2.3 Replace the removed section in `orchestration.md` with a one-line pointer (`> See docs/badcases/2026-04-24-...md for the W1.5 autopsy.`) + keep the R1–R4 rules inline since they are normative.

---

## P1 — Cleanup (G1)

### Slice C: kill dead paths
- [x] P1.1.1 Delete `.claude/pending-docs/` recursively.
- [x] P1.2.1 Inspect `.claude/PRPs/plans/kmp-claude-code-remote.plan.md` — archived into `openspec/changes/archive/2026-04-22-bootstrap/plan.md` (unambiguous 2026-04-22 date match via the pending CHANGELOG fragment).
- [x] P1.2.2 Record the decision in commit message (done in commit `4428ba4`).

---

## P2 — Progressive Loading (G2 + G1 E)

### Slice E: skills INDEX + front-matter (G1)
- [x] P2.2.1 Create `docs/skills/INDEX.md` with a `when-to-load` column.
- [x] P2.2.2 Existing per-file front-matter (`name`/`description`/`triggers`/`related-specs`) on all four skill docs was richer than the proposed `when-to-load`/`covers`/`size-lines` schema — **kept the existing schema** and reused it from the INDEX.

### Slice D: rule directory routing (G2 — runs after G1 because it touches `.claude/settings.json` which F also touches)
- [x] P2.1.1 Introduce `.claude/scripts/kotlin-rule-router.sh` (PreToolUse hook) that emits a recommended-rule hint based on the `file_path` in `tool_input` — Claude Code has no `additionalDirectories` path-glob schema, so we route via hook output instead.
    - `*Test/**/*.kt` → `coding-style.md` + `patterns.md` + `testing.md`
    - `relay/**/*.kt` → `coding-style.md` + `patterns.md` + `security.md`
    - `*.gradle.kts` → `hooks.md` + `patterns.md`
    - other `*.kt` → `coding-style.md` + `patterns.md`
- [x] P2.1.2 Smoke-tested all four routes via stdin JSON payloads — each emits the expected rule list; non-Kotlin files are silently skipped.

---

## P3 — Observability (G3)

### Slice F: archive index script + SessionEnd hint
- [x] P3.1.1 `.claude/scripts/gen-archive-index.sh` — scans `openspec/changes/archive/*/`, extracts date+id from directory name and first `## Why` paragraph, emits sorted table, idempotent (diff-compare before write).
- [x] P3.1.2 Script ran once, produced `openspec/changes/archive/INDEX.md` with four archived changes (reverse-chronological).
- [x] P3.2.1 `.claude/scripts/session-failure-cluster-check.sh` registered as the last step in `session-end-docs.sh` — prints hint to stderr when `red >= 3 && green == 0` on the current branch window.
- [x] P3.2.2 Smoke-tested via `FCLUSTER_FAKE_LOG=...` env-override (positive: hint emitted / negative: silent). No throwaway branch needed — test hook injects fake log to avoid touching git state.

---

## Validation gate

- [x] V1 `openspec validate add-doc-lifecycle-infra --strict` → green.
- [x] V2 Spec-compliance review subagent ✅ — APPROVED-WITH-NITS; all 5 requirements PASS.
- [x] V3 Code/quality review subagent ✅ — two nits addressed in commit `c26dbf8` (tracked settings.json + deduped exports).
- [x] V4 Spec deltas intentionally present under `openspec/changes/add-doc-lifecycle-infra/specs/docs-lifecycle/spec.md` — establishes the `docs-lifecycle` capability.

---

## Dispatch order (reference)

```
Wave G1 (parallel): A + B + C + E
Wave G2 (after G1): D
Wave G3 (after G2): F
Final: dispatch reviewer subagent → archive
```
