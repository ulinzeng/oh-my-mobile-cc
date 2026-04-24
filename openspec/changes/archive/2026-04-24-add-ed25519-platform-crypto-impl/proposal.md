# Change: Implement Ed25519 via platform crypto (expect/actual)

## Why

`openspec/specs/pairing/spec.md` mandates Ed25519 session signatures, but the spec is silent on **implementation strategy**. The W1.5 plan first proposed porting RFC 8032's reference implementation as pure-Kotlin (~400 LOC of field/group arithmetic + SHA-512). On reflection this is the wrong default:

1. **Security-critical cryptography should not be reimplemented from scratch** unless there is a compelling reason. Side-channel resistance, constant-time comparisons, and field arithmetic bugs are easy to get wrong and extremely hard to audit from a reviewer's seat.
2. **All three targets (JVM, Android API 26+, iOS 14+) have vetted Ed25519 primitives available**, either in the platform SDK or via a well-maintained library. Using them costs ~50 LOC of `expect`/`actual` instead of 400 LOC of hand-rolled crypto.
3. **`openspec/project.md` §Tech Stack** already lists **Ed25519 (配对) + TLS 1.3** as a security primitive — the implementation route has simply never been formalized. This proposal formalizes it.

The tradeoff is a compile-time dependency on **BouncyCastle (JVM + Android)**. BouncyCastle is the standard choice for Android Ed25519 below API 34 (Android's native `java.security.Signature("Ed25519")` provider landed in API 34/Android 14 — we support API 26+). On iOS we use Kotlin/Native C interop with a vetted Ed25519 primitive; the W1.5 initial target is a stub that throws `NotImplementedError`, with the full iOS actual tracked as a follow-up under the `pairing` capability in W2.1 (when the iOS client is actually wired up).

## What Changes

- **ADD** to `pairing` capability: **Requirement: Ed25519 implementation uses vetted platform crypto**
  - Pure-Kotlin reimplementation of Ed25519 field/group arithmetic is explicitly **not allowed** as a default.
  - Implementations MUST use `expect`/`actual` with platform-native or well-maintained library backends.
  - JVM + Android actuals MUST use BouncyCastle 1.78+ (`org.bouncycastle:bcprov-jdk18on`).
  - iOS actual MAY stub with `NotImplementedError` until W2.1; when implemented, it MUST pass the same RFC 8032 test vectors.
- **ADD** test requirement: every actual SHALL pass the RFC 8032 §7.1 test vectors (at minimum Vector 1 + Vector 2), as contract tests that run on each target.
- **ADD** documentation: ADR-0005 at `docs/adr/0005-ed25519-platform-crypto.md` capturing:
  - Decision: platform crypto over pure-Kotlin.
  - Alternatives: pure-Kotlin RFC reference port (rejected — audit burden), libsodium JNI (rejected — adds native toolchain complexity), lazysodium-kmp (rejected — maintenance concerns).
  - Risks: BouncyCastle adds ~6 MB to Android APK; acceptable vs 400 LOC of audit burden.
- **UPDATE** `.claude/PRPs/plans/w1.5-pairing-relayclient.plan.md` Task 2 after this proposal is approved (moved out of scope of the proposal — handled by plan refresh).

## Impact

- **Affected specs**: `pairing` (adds one requirement; does not modify existing behavior; no REMOVED / RENAMED deltas).
- **Affected code** (all NEW, none modified):
  - `shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt` — `expect` declarations.
  - `shared/src/jvmMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt` — BouncyCastle actual.
  - `shared/src/androidMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt` — BouncyCastle actual (shares code with JVM where possible).
  - `shared/src/iosMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt` — stub with `NotImplementedError`, TODO comment linking to the W2.1 follow-up change ID.
- **Affected dependencies**: `gradle/libs.versions.toml` gains a `bouncycastle` version + library alias; `shared/build.gradle.kts` wires it into `jvmMain` + `androidMain`.
- **Affected bundle size**: BouncyCastle adds ~6 MB to the Android APK. Documented in ADR-0005 as an accepted tradeoff vs 400 LOC of audit burden; `minifyEnabled` + ProGuard rules for BC will land with the Android app build in W2.
- **No behavioral change** to the pairing handshake from the wire protocol perspective — ClientHello shape, canonical signing input, and verifier contract all remain as specified in the existing `pairing/spec.md`.
