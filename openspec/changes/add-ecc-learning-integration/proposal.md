# Change Proposal: add-ecc-learning-integration

## Why

The existing `docs-lifecycle` capability establishes durable
`docs/badcases/` + `docs/skills/` corpuses, but **filling those
corpuses is still manual**. Meanwhile, this project already has the
ECC `continuous-learning-v2` observer hook running at the user-global
level — `~/.claude/homunculus/projects/1b870545265a/observations.jsonl`
contains real tool-event observations for this project. The machine
that produces learning signals is already running; only the downstream
pipes to project docs are missing.

Rather than re-implement the observation + evolution pipeline (as a
prior draft proposed with `add-self-evolution-loop`), this change
**wires the existing ECC output into project doc trees**:

- Unlock `continuous-learning-v2` from ECC's `skills-disabled/`.
- Add a schema-mapping post-processor that turns ECC `evolved/skills/*.md`
  into project `docs/skills/drafts/*.md` with correct front-matter
  (`name` / `triggers` / `related-specs` / `evolved-from` provenance).
- Add a separate failure-cluster → badcase writer so automatic
  post-mortems land in `docs/badcases/<date>-auto-<hash>.md`.
- Keep the **human gate**: evolved skills land in `drafts/`, only
  promoted to `docs/skills/` by user `mv`.

## What Changes

This extends the existing `docs-lifecycle` capability. **No new
capability.** Two new Requirements are added to
`openspec/specs/docs-lifecycle/spec.md`:

- **ECC-observed skill integration** — project tooling MUST be able to
  import ECC-evolved skills into `docs/skills/drafts/` with schema
  normalization + provenance + secret redaction.
- **Automated cluster-to-badcase mapping** — when the
  failure-cluster check fires, a badcase MUST be written automatically
  to `docs/badcases/<date>-auto-<hash>.md` with `status: auto-generated`.

Four new files, all ≤ 100 lines, all TDD-first:

- **S1** `.claude/scripts/prune-ecc.sh` — append `continuous-learning-v2`
  to `KEEP_SKILLS`; re-run prune.
- **S2** `.claude/scripts/ensure-observer-hook.sh` — idempotent hook
  registration verifier. Does NOT mutate user-global settings; only
  reports `PASS` / `NEEDS_USER_ACTION`.
- **S3** `.claude/scripts/ecc-skill-to-docs.sh` — schema-mapping
  post-processor. Reads `~/.claude/homunculus/.../evolved/skills/*.md`,
  emits `docs/skills/drafts/<name>.md` with front-matter
  normalization + secret redaction.
- **S4** `.claude/scripts/failure-to-badcase.sh` — called by
  `session-failure-cluster-check.sh` when the TDD-stall signal fires.
  Writes one badcase per unique signal hash.

## Impact

**Affected files:**
- `.claude/scripts/prune-ecc.sh` (edit)
- `.claude/scripts/ensure-observer-hook.sh` (new)
- `.claude/scripts/ecc-skill-to-docs.sh` (new)
- `.claude/scripts/failure-to-badcase.sh` (new)
- `.claude/scripts/session-failure-cluster-check.sh` (edit — call S4)
- `.claude/tests/**` (new — fixtures + runner per slice)
- `openspec/specs/docs-lifecycle/spec.md` (MODIFIED, not ADDED)

**Affected specs:** ONE — `docs-lifecycle` gains two Requirements.
No new capability. No runtime/build code touched.

**Risks:**
- ECC `instinct-cli.py` hard-codes `~/.claude/homunculus` — post-processor
  only **reads** from there, so upgrades don't break us unless ECC changes
  its on-disk layout. Mitigated by TDD fixtures that capture today's layout.
- User-global `~/.claude/settings.json` must not be silently mutated by
  this project — S2 is **observer / verifier only**, never writer.
- Secret redaction must cover `/Users/`, `/home/`, `AKIA…`, `.env` blobs.
  TDD fixture S3-04 asserts each case.

**NOT doing:**
- Forking or monkey-patching ECC source.
- Building our own observer daemon (redundant).
- Auto-committing generated skills or badcases.
- Cross-repo observation aggregation.
