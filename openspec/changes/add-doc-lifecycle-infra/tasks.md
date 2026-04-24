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
- [ ] P0.1.1 Create `docs/adr/INDEX.md` listing 0001–0006 with one-line summaries.
- [ ] P0.1.2 Add YAML front-matter (`status`, `date`, `depends-on`, `superseded-by?`) to each of `0001-...md` through `0006-...md`.
- [ ] P0.1.3 Verify `docs/adr/INDEX.md` renders the six entries correctly.

### Slice B: badcases directory + W1.5 autopsy extraction
- [ ] P0.2.1 Create `docs/badcases/INDEX.md` with column schema (date, slug, tag, lesson).
- [ ] P0.2.2 Create `docs/badcases/2026-04-24-w15-lead-did-not-dispatch-subagents.md` by relocating the "Anti-Patterns 实录 — W1.5 session 复盘" section from `.claude/rules/orchestration.md`.
- [ ] P0.2.3 Replace the removed section in `orchestration.md` with a one-line pointer (`> See docs/badcases/2026-04-24-...md for the W1.5 autopsy.`) + keep the R1–R4 rules inline since they are normative.

---

## P1 — Cleanup (G1)

### Slice C: kill dead paths
- [ ] P1.1.1 Delete `.claude/pending-docs/` recursively.
- [ ] P1.2.1 Inspect `.claude/PRPs/plans/kmp-claude-code-remote.plan.md` — if it maps to an archived change (`bootstrap` or earlier W* work), move it under that change's archive dir as `plan.md`; otherwise delete it and note in commit message.
- [ ] P1.2.2 Record the decision in commit message (`chore(docs): archive/delete kmp-...plan.md because …`).

---

## P2 — Progressive Loading (G2 + G1 E)

### Slice E: skills INDEX + front-matter (G1)
- [ ] P2.2.1 Create `docs/skills/INDEX.md` with a `when-to-load` column.
- [ ] P2.2.2 Add YAML front-matter (`when-to-load`, `covers`, `size-lines`) to `ansi-parser-deep-dive.md`, `compose-canvas-terminal.md`, `openspec-workflow.md`, `stream-json-protocol.md`.

### Slice D: rule directory routing (G2 — runs after G1 because it touches `.claude/settings.json` which F also touches)
- [ ] P2.1.1 Update `.claude/settings.json` `additionalDirectories` / rule-loading config so that Kotlin rule files are loaded by file-path glob rather than bulk.
    - `shared/src/**Test/**/*.kt` → load `kotlin/testing.md` (+ coding-style, patterns)
    - `shared/src/**/*.kt` general → load `coding-style.md` + `patterns.md`
    - `relay/**/*.kt` → load `coding-style.md` + `patterns.md` + `security.md`
    - `**/build.gradle.kts` → load `hooks.md` + `patterns.md`
- [ ] P2.1.2 Test the new routing by making a trivial edit in each of the four target file categories and confirming the expected rule-file set loads.

---

## P3 — Observability (G3)

### Slice F: archive index script + SessionEnd hint
- [ ] P3.1.1 Write `.claude/scripts/gen-archive-index.sh` that:
    - scans `openspec/changes/archive/*/`
    - extracts date + change-id from directory name and first `## Why` paragraph from `proposal.md`
    - emits `openspec/changes/archive/INDEX.md` as a sorted Markdown table
    - is idempotent (diff-only write).
- [ ] P3.1.2 Run the script once to produce the initial INDEX and commit it.
- [ ] P3.2.1 Add a SessionEnd entry in `.claude/settings.json` that invokes a tiny inline script:
    - count `[red]` commits since session start
    - count `[green]` commits since session start
    - if `red >= 3 && green == 0`, print to stderr: "Failure-cluster hint: consider writing a badcase in docs/badcases/."
- [ ] P3.2.2 Smoke-test the hook by simulating 3 `[red]` commits in a throwaway branch (or dry-run via `--check` flag) and confirm the hint fires.

---

## Validation gate

- [ ] V1 `openspec validate add-doc-lifecycle-infra --strict` → green.
- [ ] V2 Spec-compliance review subagent ✅.
- [ ] V3 Code/quality review subagent ✅ (per user request).
- [ ] V4 No capability `specs/` deltas — confirmed no files under `openspec/changes/add-doc-lifecycle-infra/specs/`.

---

## Dispatch order (reference)

```
Wave G1 (parallel): A + B + C + E
Wave G2 (after G1): D
Wave G3 (after G2): F
Final: dispatch reviewer subagent → archive
```
