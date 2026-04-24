## ADDED Requirements

### Requirement: Ed25519 implementation uses vetted platform crypto

The system SHALL implement Ed25519 keypair generation, signing, and verification using **`expect`/`actual` declarations** backed by platform-native or well-maintained library primitives. Pure-Kotlin reimplementation of Ed25519 field arithmetic, group arithmetic, or SHA-512 is explicitly **prohibited** as a default implementation strategy.

- JVM (`:relay`) actual MUST use BouncyCastle 1.78+ (`org.bouncycastle:bcprov-jdk18on`).
- Android (`androidMain`) actual MUST use BouncyCastle 1.78+; a future migration to `java.security.Signature("Ed25519")` MAY replace BouncyCastle when `minSdk` rises to 34.
- iOS (`iosMain`) actual MAY stub with `NotImplementedError` during W1.5 while the iOS client is not yet wired; when the actual ships, it MUST pass the same RFC 8032 test vectors used by the JVM actual.

Every actual SHALL pass **RFC 8032 §7.1 Ed25519 test vectors** (at minimum Vector 1 and Vector 2) as contract tests. Contract tests SHALL live in `shared/src/commonTest/` and run on every supported target.

#### Scenario: JVM actual passes RFC 8032 Vector 1
- **WHEN** the JVM Ed25519 actual signs the empty message with RFC 8032 Vector 1 seed `9d61b19d...7f60`
- **THEN** the resulting signature byte-for-byte equals the RFC-prescribed signature `e5564300...a100b`

#### Scenario: JVM actual passes RFC 8032 Vector 2
- **WHEN** the JVM Ed25519 actual signs the single-byte message `0x72` with RFC 8032 Vector 2 seed `4ccd089b...a6fb`
- **THEN** the resulting signature byte-for-byte equals the RFC-prescribed signature `92a009a9...0c00`

#### Scenario: iOS stub is explicit
- **WHEN** the iOS actual's `sign` or `verify` is invoked during W1.5
- **THEN** it throws `NotImplementedError` with a message pointing to the follow-up change ID that will deliver the real iOS actual

#### Scenario: Pure-Kotlin reimplementation blocked at review
- **WHEN** a diff proposes adding `FieldElement`, `Scalar`, `EdwardsPoint`, or hand-rolled `Sha512` classes inside `shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/`
- **THEN** code review SHALL reject the change unless it is accompanied by an approved superseding OpenSpec proposal that removes this requirement
