## Context

W1.5 (Pairing + RelayClient) depends on Ed25519 signature verification on the relay side and Ed25519 signing on the mobile side. The original W1.5 plan proposed porting the RFC 8032 reference implementation as pure-Kotlin code in `shared/core/crypto/` (~400 LOC). On review this is the wrong default for three reasons:

1. **Audit burden** — hand-rolled constant-time field arithmetic is extremely hard to review. A single carry-propagation bug breaks the scheme silently.
2. **Side-channel risk** — RFC reference implementations are correctness-oriented, not timing-safe. Writing a timing-safe impl is harder still.
3. **Availability** — all three target platforms have vetted Ed25519 primitives already. Reaching for them is faster and safer.

This proposal formalizes the implementation strategy as a requirement under the existing `pairing` capability, then amends the W1.5 plan to match.

## Goals / Non-Goals

**Goals**
- Lock in "platform crypto via `expect`/`actual`" as the canonical Ed25519 strategy for this repo.
- Add one BouncyCastle dependency (JVM + Android); no other changes to module graph.
- Keep the iOS stub explicit so the gap is visible (vs silently crashing at runtime).
- Make the RFC 8032 test vectors a contract test that every actual MUST pass.

**Non-Goals**
- Implementing the iOS actual — that is a W2.1 follow-up change (new change ID: `add-ios-ed25519-actual`).
- Switching Android to native `java.security.Signature("Ed25519")` — deferred until `minSdk` rises to 34.
- TLS 1.3 session work — orthogonal, lives under its own capability slice when scheduled.

## Decisions

### Decision 1: BouncyCastle over pure-Kotlin RFC port

**What**: Use `org.bouncycastle:bcprov-jdk18on` 1.78+ on JVM + Android; iOS stub with `NotImplementedError` for W1.5.

**Why**:
- BouncyCastle has been battle-tested for ~20 years and audited repeatedly.
- `Ed25519Signer` exposes the exact RFC 8032 shape we need.
- ~6 MB jar cost on Android is acceptable for a security primitive; R8 minification can prune most of it since we only use Ed25519.
- Android's native `java.security.Signature("Ed25519")` provider requires API 34; our `minSdk` is 26. BC bridges the gap without requiring two Android actuals.

**Alternatives considered**:
- **Pure-Kotlin RFC 8032 port** (~400 LOC): rejected — audit burden and side-channel risk outweigh the dependency-avoidance benefit.
- **libsodium via JNI** (e.g. `lazysodium-kmp`): rejected — adds native toolchain complexity (requires CMake build on iOS, NDK on Android); its maintenance cadence is slower than BC.
- **JDK 15+ native `java.security.Signature("Ed25519")`**: works on JVM 15+ and Android 34+, not on Android 26–33 which we must support. Could be added as a second JVM actual behind a runtime capability check, but the complexity is not worth the ~3 MB savings today.

### Decision 2: iOS stub throws `NotImplementedError` instead of silent fallback

**What**: `iosMain/.../Ed25519.kt` throws immediately from `sign` / `verify`, with message pointing to the follow-up change ID.

**Why**: A silent fallback (returning an empty `ByteArray` or always returning `false` from `verify`) is a security bug pattern — tests and reviewers miss it. An explicit throw makes the gap visible in CI if any iOS test path hits this code.

**Alternative considered**: Defer the whole iOS target of `shared/` until W2.1. Rejected — we want the iOS framework to keep compiling so W1.5's other iOS-facing work (future `TransportPort` for `iosMain`) is not blocked.

### Decision 3: RFC 8032 vectors as contract tests in `commonTest`

**What**: The test file already committed in the W1.5 RED phase (`shared/src/commonTest/kotlin/io/ohmymobilecc/core/crypto/Ed25519Test.kt`) becomes the contract. It runs on every target the `shared` module ships to.

**Why**: KMP `commonTest` already runs against each target's actual, so the vectors validate every platform backend automatically. iOS stub is excluded from the contract test run until W2.1 — easiest is to gate with `@Test` + `try { ... } catch (e: NotImplementedError) { /* tolerated on ios until W2.1 */ }` in the iOS test source set, but the simpler approach is to keep the common contract test as-is and let iOS fail loudly (we can add `@IgnoreIos` equivalent only when the iOS surface actually compiles tests).

For W1.5: the contract test runs on JVM target only (`:shared:jvmTest`). We explicitly document the iOS gap in the ADR.

## Risks / Trade-offs

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| BouncyCastle APK bloat | High (definite) | Medium | ADR-0005 accepts the cost; R8 minify when we ship Android app in W2. |
| BC `Ed25519Signer` API drift across BC versions | Low | Low | Pin to `1.78+`; bump intentionally with a proposal. |
| iOS stub reached in production | Low | High | `@Throws(NotImplementedError)` + explicit error message; contract tests for the iOS actual land with W2.1 before Inbox flow. |
| Reviewer unfamiliar with BC API | Medium | Low | Add code-level KDoc pointing to BC's `Ed25519Signer` Javadoc; ADR explains the shape. |

## Migration Plan

No production deployment exists yet — this is a W1.5 implementation choice, not a migration from live code. After this proposal is approved:

1. Merge this proposal.
2. Update `.claude/PRPs/plans/w1.5-pairing-relayclient.plan.md` Task 2 to match the actuals strategy.
3. Execute W1.5 implementation; Ed25519 contract tests from the already-committed RED phase now drive the JVM actual to green.
4. Archive this proposal at the same time as W1.5 merges to main (`openspec archive add-ed25519-platform-crypto-impl --yes`).

Rollback: if BouncyCastle introduces an unforeseen problem, the `expect` declaration lets us swap actuals without touching callers. A follow-up proposal would declare the new backend strategy.

## Open Questions

- None blocking. Minor open items for W2.1:
  - Exact iOS actual: CryptoKit (iOS 17+) vs small pinned C lib vs BC-via-JNI? To be decided in the W2.1 change proposal when we have the iOS bundle-size data.
