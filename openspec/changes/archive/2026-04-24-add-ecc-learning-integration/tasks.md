# Tasks: add-ecc-learning-integration

TDD workflow: every slice is `[red]` → `[green]` → `[refactor]`. Tests
live under `.claude/tests/`. Slices are file-disjoint and dispatched in
one G1 wave.

| Slice | Agent              | Files                                                           |
|-------|--------------------|-----------------------------------------------------------------|
| S1    | general-purpose    | `.claude/scripts/prune-ecc.sh` (edit)                           |
| S2    | general-purpose    | `.claude/scripts/ensure-observer-hook.sh` (new)                 |
| S3    | general-purpose    | `.claude/scripts/ecc-skill-to-docs.sh` (new) + fixtures         |
| S4    | general-purpose    | `.claude/scripts/failure-to-badcase.sh` (new) + hook wiring     |

---

## Slice S1 — Unlock continuous-learning-v2

- [x] S1.1 **[red]** `.claude/tests/prune-ecc-keep.test.sh` asserts `grep -c 'continuous-learning-v2' .claude/scripts/prune-ecc.sh` ≥ 1; runs initially fails.
- [x] S1.2 **[green]** Add `continuous-learning-v2` to `KEEP_SKILLS` array in `prune-ecc.sh`; test passes.
- [x] S1.3 **[refactor]** Re-run prune script; confirm `ls ~/.claude/skills/continuous-learning-v2/SKILL.md` exists OR the skill is tracked in the new `KEEP_SKILLS` line for next prune invocation.

## Slice S2 — Observer hook verifier

- [x] S2.1 **[red]** `.claude/tests/hook-registered.test.sh` asserts `ensure-observer-hook.sh` exits 0 when hook exists in `~/.claude/settings.json` and `PASS` is printed; exits 0 with `NEEDS_USER_ACTION` when missing.
- [x] S2.2 **[green]** Write `ensure-observer-hook.sh`: read-only verifier; uses `jq` or pure awk to check for any hook whose command contains `observe.sh`. Emits one-line status.
- [x] S2.3 **[refactor]** Ensure the script is idempotent (second run same output) and never writes to user settings. Add `HOME` override for test isolation.

## Slice S3 — ECC skill → docs/skills/drafts schema mapper

- [x] S3.1 **[red]** Fixtures:
    - `.claude/tests/fixtures/ecc-skill/01-minimal.md` (trigger: string, no related-specs)
    - `.claude/tests/fixtures/ecc-skill/02-array-trigger.md` (triggers already array)
    - `.claude/tests/fixtures/ecc-skill/03-with-specs.md` (related-specs pre-set — must not overwrite)
    - `.claude/tests/fixtures/ecc-skill/04-abspath-leak.md` (contains `/Users/ulinzeng/…` + `AKIA…` token)
    - `.claude/tests/ecc-skill-to-docs.test.sh` — runs converter on each fixture, diffs expected output. Initially fails because converter doesn't exist.
- [x] S3.2 **[green]** Write `.claude/scripts/ecc-skill-to-docs.sh`:
    - arg: source file path
    - parse YAML front-matter with awk
    - map `id` → `name`, `trigger:str` → `triggers:[str]`, keep `triggers` array untouched
    - build `description` as `"[{domain}] {action_description}"`
    - keep `related-specs` if present; omit if absent (do NOT auto-fill)
    - add `evolved-from: <source-path>` + `evolved-confidence: <value>` + `draft: true`
    - redact `/Users/...`, `/home/...`, `AKIA[0-9A-Z]{16}`, lines containing `.env` values
    - write to `docs/skills/drafts/<name>.md`
- [x] S3.3 **[refactor]** All 4 fixtures pass; idempotent write (same input → same output, diff-check); single-responsibility helpers (normalize_triggers, redact, build_frontmatter).

## Slice S4 — Failure cluster → badcase

- [x] S4.1 **[red]** Fixtures:
    - `.claude/tests/fixtures/gitlog/pos-3red-0green.txt` (3 `[red]` subjects)
    - `.claude/tests/fixtures/gitlog/neg-1red-1green.txt`
    - `.claude/tests/failure-to-badcase.test.sh` — invokes script with `GITLOG_FAKE_INPUT=<fixture>`; positive case writes file, negative writes nothing.
- [x] S4.2 **[green]** Write `.claude/scripts/failure-to-badcase.sh`:
    - reads git log via `FCLUSTER_FAKE_LOG` env (reuse existing env convention from session-failure-cluster-check.sh) OR `git log` directly
    - if `red_count >= 3 && green_count == 0`:
        - compute `hash7 = sha1(sorted_red_subjects)[0:7]`
        - write `docs/badcases/$(date +%Y-%m-%d)-auto-<hash7>.md` with front-matter `status: auto-generated`, `signal-source: [red-cluster]`, list of commit subjects as body
    - else: exit 0 silently
- [x] S4.3 **[green-wiring]** Edit `.claude/scripts/session-failure-cluster-check.sh`: after emitting the hint, also `exec` `failure-to-badcase.sh` with the same log env.
- [x] S4.4 **[refactor]** Idempotent: running twice with same cluster → existing badcase not overwritten (detect existing filename by hash). Negative fixture writes nothing.

---

## Integration / G2 (lead-run after G1 merged)

- [x] G2.1 `bash .claude/tests/run-all.sh` passes (meta-runner that finds every `*.test.sh` under `.claude/tests/` and runs it).
- [x] G2.2 Manual smoke: craft a fake `~/.claude/homunculus/.../evolved/skills/fake.md`, run the converter, confirm `docs/skills/drafts/fake.md` appears.
- [x] G2.3 Manual smoke: set `FCLUSTER_FAKE_LOG` to a 3-red fixture, run `session-failure-cluster-check.sh`, confirm `docs/badcases/<today>-auto-<hash>.md` appears.

## Validation gate

- [x] V `openspec validate add-ecc-learning-integration --strict` → green.
- [x] V Spec-compliance reviewer subagent ✅.
- [x] V Code/quality reviewer subagent ✅.
- [x] V Secret redaction fixture S3-04 produces zero `/Users/`, zero `AKIA`, zero `.env` matches in output.
