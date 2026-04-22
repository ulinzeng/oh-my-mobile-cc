# Plan W1.1: CCEvent + WireMessage round-trip (protocol foundation)

> Sub-plan of `.claude/PRPs/plans/kmp-claude-code-remote.plan.md`.
> This plan covers Task 1.1 [RED] + 1.2 [GREEN] of Phase W1 in Plan v2.
> Scope is tight: only the protocol sealed models in `shared/core/protocol/`.
> No relay changes, no UI changes, no ApprovalBridge (that's W1.4, gated by
> an upcoming `add-approval-bridge-hook` proposal).

## Summary

Implement the two sealed hierarchies that define the project's wire protocol:

1. **`CCEvent`** — typed model of `claude -p --output-format stream-json`
   stdout events (what the relay **reads** from CC).
2. **`WireMessage`** — typed model of mobile↔relay WebSocket frames
   (what relay **and** mobile serialize).

Both must:
- Round-trip via `kotlinx.serialization` (`classDiscriminator` — `type` for
  CCEvent, `op` for WireMessage).
- Deserialize unknown discriminators to an `Unknown(raw: JsonObject)` variant
  instead of throwing.
- Preserve complete raw JSON for forward-compat.

This is the foundation for **every** later feature (Chat, Approval Inbox,
Terminal, FileSync). Nothing else can be written without it.

## Patterns to Mirror

### MIRROR-1: kotlinx.serialization sealed with class discriminator

Reference (from Plan v2 "Patterns to Mirror" + real KMP use):

```kotlin
// shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/CCEvent.kt
@Serializable
@JsonClassDiscriminator("type")
public sealed class CCEvent {
    public abstract val raw: JsonObject

    @Serializable
    @SerialName("system")
    public data class System(
        val subtype: String? = null,
        val session_id: String? = null,
        override val raw: JsonObject = JsonObject(emptyMap())
    ) : CCEvent()

    @Serializable
    @SerialName("assistant")
    public data class Assistant(
        val message: JsonObject? = null,
        override val raw: JsonObject = JsonObject(emptyMap())
    ) : CCEvent()

    // ...
}
```

**GOTCHA 1**: `JsonClassDiscriminator` requires opt-in
`@OptIn(ExperimentalSerializationApi::class)` on the sealed declaration or
file-level. Use file-level.

**GOTCHA 2**: `Json { classDiscriminator = "type" }` in the `Json` instance
is NOT enough — need `@JsonClassDiscriminator` annotation on the sealed
class because the global config is overridden per hierarchy.

**GOTCHA 3**: `Unknown` variant cannot participate in polymorphic
deserialization via the standard path — kotlinx.serialization will throw
`SerializationException: Serializer for subclass 'X' is not found`.
Use a **custom `JsonContentPolymorphicSerializer`** that reads the
discriminator manually and falls back to `Unknown(raw)`.

### MIRROR-2: Test structure

Reference Plan v2 `TDD_TEST_STRUCTURE`:

```kotlin
// shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/CCEventTest.kt
class CCEventTest {
    private val json = ProtocolJson.default

    @Test fun `decodes system init event`() {
        val line = """{"type":"system","subtype":"init","session_id":"s1"}"""
        val ev = json.decodeFromString<CCEvent>(line)
        assertIs<CCEvent.System>(ev)
        assertEquals("init", (ev as CCEvent.System).subtype)
    }

    @Test fun `decodes unknown type as Unknown`() {
        val line = """{"type":"not_yet_specified","foo":"bar"}"""
        val ev = json.decodeFromString<CCEvent>(line)
        assertIs<CCEvent.Unknown>(ev)
        assertEquals("not_yet_specified", ev.raw["type"]?.jsonPrimitive?.content)
    }
}
```

### MIRROR-3: Existing PlaceholderTest in the same module

Path: `shared/src/commonTest/kotlin/io/ohmymobilecc/PlaceholderTest.kt`
Style: `kotlin.test` + `@Test` + backticked method names + `assertEquals`.
Stay consistent.

## Files to Change

### CREATE — production code (`shared/src/commonMain/`)

| File | Purpose |
|---|---|
| `kotlin/io/ohmymobilecc/core/protocol/ProtocolJson.kt`         | Shared `Json` instance with `encodeDefaults=false`, `ignoreUnknownKeys=true`, `isLenient=false`. Single source of truth. |
| `kotlin/io/ohmymobilecc/core/protocol/CCEvent.kt`              | Sealed class + variants: `System`, `User`, `Assistant`, `Result`, `StreamEvent`, `HookStarted`, `HookResponse`, `HookProgress`, `Unknown`. |
| `kotlin/io/ohmymobilecc/core/protocol/CCEventSerializer.kt`    | Custom `JsonContentPolymorphicSerializer<CCEvent>` with Unknown fallback. |
| `kotlin/io/ohmymobilecc/core/protocol/WireMessage.kt`          | Sealed class + variants for 4 op groups: chat.*, approval.*, terminal.*, file.*, plus `Unknown`. First cut carries only the minimum subset needed for the tests (see below). |
| `kotlin/io/ohmymobilecc/core/protocol/WireMessageSerializer.kt`| Custom polymorphic serializer with Unknown fallback. |

### CREATE — tests (`shared/src/commonTest/`)

| File | Purpose |
|---|---|
| `kotlin/io/ohmymobilecc/core/protocol/CCEventTest.kt`          | Round-trip for each known type + Unknown fallback + real capture decode smoke (reads `real_captures/03-hook-bridge-approved.ndjson` and confirms parser emits a stream of typed events with zero exceptions). |
| `kotlin/io/ohmymobilecc/core/protocol/WireMessageTest.kt`      | Round-trip for each declared op + Unknown fallback. |
| `kotlin/io/ohmymobilecc/core/protocol/ProtocolJsonTest.kt`     | Config invariants: `ignoreUnknownKeys=true`, `encodeDefaults=false` (unit test the Json instance itself). |

### DELETE (now superseded)

| File | Reason |
|---|---|
| `shared/src/commonMain/kotlin/io/ohmymobilecc/Placeholder.kt`          | Placeholder marker from W0; real protocol code replaces it. |
| `shared/src/commonTest/kotlin/io/ohmymobilecc/PlaceholderTest.kt`      | Ditto. The new tests cover real behavior. |

**GOTCHA**: If any other code currently imports `SHARED_MODULE_MARKER`, delete
those imports. As of last check, nothing depends on it.

### UPDATE

| File | Why |
|---|---|
| `shared/build.gradle.kts` | Ensure `kotlinx.serialization` runtime dep is in `commonMain`; ensure `enableLanguageFeature("ContextReceivers")` is NOT required. If already present (W0 scaffold did include kotlinx.serialization), no change. Verify with `./gradlew :shared:dependencies --configuration commonMainCompileClasspath` if in doubt. |

## Step-by-Step Tasks

> Each task sub-bullet must be validated before moving to the next.
> `RED` steps MUST fail; `GREEN` steps MUST pass.

### Task 1 — [RED] Write failing tests for `ProtocolJson`
- **ACTION**: Create `ProtocolJsonTest.kt` asserting:
  - `ProtocolJson.default.configuration.ignoreUnknownKeys == true`
  - `ProtocolJson.default.configuration.encodeDefaults == false`
  - `ProtocolJson.default.configuration.isLenient == false`
- **VALIDATE**: `./gradlew :shared:jvmTest --tests "*.ProtocolJsonTest"` → FAIL (file doesn't compile; class missing)

### Task 2 — [GREEN] Create `ProtocolJson`
- **ACTION**: `ProtocolJson.kt` exposes `public val ProtocolJson.default: Json`
  configured with the three flags above.
- **VALIDATE**: `./gradlew :shared:jvmTest --tests "*.ProtocolJsonTest"` → PASS

### Task 3 — [RED] Write failing tests for `CCEvent`
- **ACTION**: `CCEventTest.kt` with:
  - `decodes system init event` (known type round-trip)
  - `decodes assistant with tool_use block`
  - `decodes unknown type as Unknown preserving raw JSON`
  - `decodes hook_started PreToolUse event` (per updated protocol spec)
  - `decodes every line of real_captures/03-hook-bridge-approved.ndjson without exception` (smoke)
- **VALIDATE**: FAIL (CCEvent classes missing)

### Task 4 — [GREEN] Implement `CCEvent` + `CCEventSerializer`
- **ACTION**:
  - Declare sealed variants: `System`, `User`, `Assistant`, `Result`, `StreamEvent`, `HookStarted`, `HookResponse`, `HookProgress`, `Unknown`.
  - Implement `JsonContentPolymorphicSerializer` that reads `type` discriminator:
    - On `"system"` → check `subtype`: if in `{"hook_started","hook_response","hook_progress"}` route to the corresponding hook variant; else `System`.
    - Known top-level `type` values → matching variant.
    - Anything else → `Unknown(raw)`.
  - `raw: JsonObject` on every variant stores the entire input for forward-compat.
- **VALIDATE**: all Task 3 tests PASS. Re-run the real-capture smoke test and assert: at least 1 `Assistant` with `tool_use`, at least 1 `HookStarted` with `hookEvent="PreToolUse"`, at least 1 `HookResponse` with `exitCode=0`, all other lines are known or `Unknown` (not thrown).

### Task 5 — [RED] Write failing tests for `WireMessage`
- **ACTION**: `WireMessageTest.kt` with:
  - `round-trip chat.message` (trivial echo)
  - `round-trip approval.requested` (the first Inbox message we care about)
  - `round-trip approval.responded`
  - `round-trip approval.expired`
  - `decodes unknown op as Unknown`
- **VALIDATE**: FAIL

### Task 6 — [GREEN] Implement `WireMessage` + `WireMessageSerializer`
- **ACTION**: Declare minimal-but-complete sealed shape:
  ```kotlin
  sealed class WireMessage {
      abstract val raw: JsonObject

      @SerialName("chat.message") data class ChatMessage(...)
      @SerialName("approval.requested") data class ApprovalRequested(
          val approvalId: String,
          val sessionId: String,
          val tool: String,
          val input: JsonObject,
          val proposedAt: Long,
          override val raw: JsonObject
      )
      @SerialName("approval.responded") data class ApprovalResponded(...)
      @SerialName("approval.expired")   data class ApprovalExpired(...)
      data class Unknown(override val raw: JsonObject)
  }
  ```
  - Terminal / file groups: stub out as **single** placeholder variant each
    (`TerminalOutput`, `FileListRequest`) so the sealed group exists but we
    don't over-commit before W3/W4 specs.
- **VALIDATE**: all Task 5 tests PASS.

### Task 7 — [REFACTOR] Clean up
- **ACTION**:
  - Extract `ProtocolJsonFactory` if serializer wiring duplicates.
  - Move `raw: JsonObject` into a shared interface if helpful (probably not).
  - Remove `Placeholder.kt` and `PlaceholderTest.kt`; confirm no broken refs.
- **VALIDATE**: `./gradlew :shared:jvmTest` still green. `./gradlew :shared:detekt :shared:ktlintCheck` green.

### Task 8 — Verify coverage gate (informational in W1; hard in W2+)
- **ACTION**: `./gradlew :shared:koverHtmlReport`; open `shared/build/reports/kover/html/index.html`.
- **VALIDATE**: `core/protocol/**` line coverage ≥ 90%. If below, add tests.

## Validation Commands

```bash
# Level 1 — static
./gradlew :shared:detekt :shared:ktlintCheck

# Level 2 — unit tests
./gradlew :shared:jvmTest

# Level 3 — full shared build (JVM target only at W1)
./gradlew :shared:compileKotlinJvm :shared:jvmTest

# Level 4 — coverage (informational at W1)
./gradlew :shared:koverHtmlReport

# Level 5 — openspec still valid (sanity; we don't change specs here)
openspec validate --specs --strict
```

## Testing Strategy

### Unit tests (all in `:shared:jvmTest`)

| Test | Covers |
|---|---|
| `ProtocolJsonTest` | Json instance config flags |
| `CCEventTest.decodeKnown_system_init`            | happy path, System variant |
| `CCEventTest.decodeKnown_assistant_with_toolUse` | nested JsonObject preservation |
| `CCEventTest.decodeKnown_result_success`         | Result variant, permission_denials carried in raw |
| `CCEventTest.decodeUnknown_type`                 | Unknown fallback preserves raw |
| `CCEventTest.decode_hookStarted_preToolUse`      | HookStarted variant |
| `CCEventTest.decode_hookResponse`                | HookResponse variant |
| `CCEventTest.decode_realCapture03`               | smoke: full NDJSON file parses cleanly |
| `WireMessageTest.roundTrip_chatMessage`          | encode+decode equality |
| `WireMessageTest.roundTrip_approvalRequested`    | first Inbox payload |
| `WireMessageTest.roundTrip_approvalResponded`    | decision payload |
| `WireMessageTest.roundTrip_approvalExpired`      | timeout payload |
| `WireMessageTest.decodeUnknown_op`               | Unknown fallback |

### Edge cases checklist

- [ ] Unknown `type` with no other fields → `Unknown(raw={"type":"x"})`.
- [ ] JSON with extra unknown fields on known type → decoded, extra fields land in `raw` (not lost).
- [ ] Empty JSON object `{}` → should `Unknown({})` (no `type` field).
- [ ] Malformed JSON (non-JSON line) → `SerializationException` propagates (caller decides).
- [ ] `hook_response` with `exit_code` (snake_case in CC) vs `exitCode` (our Kotlin field): verify `@SerialName("exit_code")` mapping.
- [ ] Real capture line containing `permission_denials` array in a `result` event → decoded, accessible via `raw["permission_denials"]`.

## Acceptance Criteria

- [ ] All 13+ tests pass.
- [ ] `./gradlew :shared:jvmTest` green.
- [ ] `./gradlew :shared:detekt :shared:ktlintCheck` green.
- [ ] `real_captures/03-hook-bridge-approved.ndjson` (98 lines) decodes with
      zero exceptions; at least one line each of `System`, `Assistant` (with
      tool_use), `HookStarted` (PreToolUse), `HookResponse`, `Result` variant
      observed.
- [ ] Placeholder.kt + PlaceholderTest.kt gone.
- [ ] Coverage of `core/protocol/**` ≥ 90% (informational).
- [ ] `openspec validate --specs --strict` still 5/5 (no spec drift from this
      code-only work).
- [ ] Branch `feat/w1-protocol` with conventional commits `[red]` and `[green]`
      visible in history before squash.

## Complexity / Risk

- **Complexity**: M (sealed class + custom polymorphic serializer is tricky
  but well-trodden in kotlinx.serialization).
- **Confidence**: High — pattern is proven; real fixture is already in place;
  specs are stable post-archive.

## What this plan is NOT doing

- No Ktor WebSocket wiring (W1.5 / next proposal).
- No ClaudeProcess relay wrapper (W1.3 / next proposal).
- No ApprovalBridge (W1.4 / `add-approval-bridge-hook` proposal).
- No Android or iOS app code.
- No OpenSpec change proposal — the protocol spec as it now stands (after
  `fix-approval-bridge-mechanism` archive) IS the contract. If during
  implementation we find the spec needs further adjustment, we open a new
  change proposal; we do NOT silently edit the spec.
