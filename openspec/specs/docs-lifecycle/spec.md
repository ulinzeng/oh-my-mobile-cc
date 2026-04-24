# docs-lifecycle Specification

## Purpose
TBD - created by archiving change add-doc-lifecycle-infra. Update Purpose after archive.
## Requirements
### Requirement: Indexed ADR corpus

The project MUST maintain an index of all Architecture Decision Records
under `docs/adr/` so agents can discover relevant ADRs without reading
every file.

Each ADR file MUST carry YAML front-matter with at minimum: `status`,
`date`, `depends-on`. An `INDEX.md` sibling MUST list every ADR with a
one-line summary in reverse-chronological order.

#### Scenario: Agent discovers an ADR by topic
- **WHEN** an agent needs the ADR governing Ed25519 key format
- **THEN** reading `docs/adr/INDEX.md` (≤ 100 lines) is sufficient to
  identify `0005-ed25519-platform-crypto.md` by its one-line summary
- **AND** the agent does not need to Read all six ADR files

#### Scenario: ADR files are self-describing
- **WHEN** an agent Reads a single ADR file
- **THEN** the YAML front-matter at the top declares its `status`
  (`accepted` / `superseded` / `draft`) and its `date`
- **AND** any superseded ADR declares the successor via `superseded-by`

### Requirement: Durable bad-case corpus

The project MUST maintain `docs/badcases/` as a dedicated tree for
post-mortems and autopsies. Bad cases MUST NOT live inline inside rule
files where they bloat normative content.

Each bad case MUST be a separate file named
`<YYYY-MM-DD>-<slug>.md` and MUST appear in `docs/badcases/INDEX.md`.

#### Scenario: Rule file references a bad case
- **WHEN** `.claude/rules/orchestration.md` refers to the W1.5 autopsy
- **THEN** it uses a one-line pointer (`> See docs/badcases/…`)
- **AND** the full narrative lives at the pointed-to path

#### Scenario: New autopsy is discoverable
- **WHEN** a future session goes off-rails and produces an autopsy
- **THEN** the autopsy is filed under `docs/badcases/<date>-<slug>.md`
- **AND** `docs/badcases/INDEX.md` gains a row referencing it

### Requirement: Scoped rule loading

Coding-rule files under `.claude/rules/**` MUST be loaded based on the
file paths being edited, not in bulk. The project configuration
(`.claude/settings.json`) MUST declare path-globs → rule-files so that
agents receive only the rules relevant to their current task.

#### Scenario: Editing a test file loads testing rules
- **WHEN** an agent edits a file under `shared/src/**Test/**/*.kt`
- **THEN** `.claude/rules/kotlin/testing.md` is loaded
- **AND** `.claude/rules/kotlin/security.md` is NOT loaded unless the
  task also touches a `relay/**` path

#### Scenario: Editing a build script loads hook rules
- **WHEN** an agent edits `**/build.gradle.kts`
- **THEN** `.claude/rules/kotlin/hooks.md` is loaded

### Requirement: Archive index is generated, not hand-maintained

`openspec/changes/archive/INDEX.md` MUST be producible by a script that
scans the archive directory. Hand-edited drift is not a valid state.

#### Scenario: Archive index regeneration is idempotent
- **WHEN** `.claude/scripts/gen-archive-index.sh` runs twice with no
  archive changes in between
- **THEN** the second run produces an identical file and exits 0
- **AND** no diff is written to git

#### Scenario: A newly archived change appears in the index
- **WHEN** a change is archived via `openspec archive <id> --yes`
- **AND** the regeneration script runs
- **THEN** the new change appears as a row in
  `openspec/changes/archive/INDEX.md` sorted by date descending

### Requirement: Failure-cluster hint

The SessionEnd hook MUST surface a hint when the session's git log
contains an unresolved cluster of `[red]` commits (≥ 3 red with zero
following `[green]`), suggesting the author file a bad case.

#### Scenario: Stuck TDD loop triggers the hint
- **GIVEN** a session has produced 4 commits tagged `[red]` and 0 commits
  tagged `[green]`
- **WHEN** the session ends
- **THEN** stderr receives a message pointing to `docs/badcases/` as a
  recommended next artifact

#### Scenario: Healthy red→green cycle does not trigger the hint
- **GIVEN** a session has produced 3 `[red]` commits followed by 3
  `[green]` commits
- **WHEN** the session ends
- **THEN** the hint is not emitted

