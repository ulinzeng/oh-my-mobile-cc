## 1. Dependency wiring

- [x] 1.1 Add `bouncycastle = "1.78"` to `[versions]` in `gradle/libs.versions.toml`
- [x] 1.2 Add `bouncycastle-bcprov = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }` to `[libraries]`
- [x] 1.3 Wire `implementation(libs.bouncycastle.bcprov)` into `shared/build.gradle.kts` `jvmMain.dependencies` and `androidMain.dependencies`
- [x] 1.4 Confirm baseline compiles: `./gradlew :shared:compileKotlinJvm :shared:compileDebugKotlinAndroid`

## 2. `expect` API surface

- [x] 2.1 Create `shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt` declaring:
  - `data class Ed25519KeyPair(val secretKey: ByteArray, val publicKey: ByteArray)`
  - `expect object Ed25519 { fun keypair(seed: ByteArray): Ed25519KeyPair; fun sign(secretKey: ByteArray, message: ByteArray): ByteArray; fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean }`

## 3. JVM actual (BouncyCastle)

- [x] 3.1 Create `shared/src/jvmMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt` using `org.bouncycastle.crypto.signers.Ed25519Signer` + `Ed25519PrivateKeyParameters` + `Ed25519PublicKeyParameters`
- [x] 3.2 Ensure `keypair(seed)` derives the public key from seed per RFC 8032 §5.1.5 (BC handles this natively via `Ed25519PrivateKeyParameters(seed, 0)` then `.generatePublicKey()`)
- [x] 3.3 Ensure `secretKey` is 64 bytes `seed || publicKey` (RFC §5.1.5); sign slices the first 32 bytes as seed before signing
- [x] 3.4 Run `./gradlew :shared:jvmTest --tests "*Ed25519Test*"` — RFC 8032 vectors pass byte-for-byte

## 4. Android actual (BouncyCastle)

- [x] 4.1 Create `shared/src/androidMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt` identical to the JVM actual (BouncyCastle bcprov-jdk18on is bytecode-compatible with Android API 26+)
- [x] 4.2 Confirm `./gradlew :shared:compileDebugKotlinAndroid :shared:compileReleaseKotlinAndroid` succeeds
- [x] 4.3 Add ProGuard keep rules note to ADR-0005 for future `minifyEnabled` R8 pass

## 5. iOS actual (stub for W1.5)

- [x] 5.1 Create `shared/src/iosMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt` with all three methods throwing `NotImplementedError("iOS Ed25519 actual ships in W2.1 under change id 'add-ios-ed25519-actual'")`
- [x] 5.2 Confirm iOS simulator compiles: `./gradlew :shared:compileKotlinIosSimulatorArm64` succeeds

## 6. Documentation

- [x] 6.1 Create `docs/adr/0005-ed25519-platform-crypto.md` capturing decision + alternatives + risks
- [x] 6.2 Update `openspec/project.md` §Tech Stack: add BouncyCastle row (JVM + Android scope, "Ed25519 backend for pairing")

## 7. Validation

- [x] 7.1 `openspec validate add-ed25519-platform-crypto-impl --strict` returns green
- [x] 7.2 `./gradlew :shared:jvmTest` green (RFC 8032 vectors pass; iOS stub compile-only)
- [x] 7.3 `./gradlew :shared:ktlintCheck :shared:detekt` green
- [ ] 7.4 After merge of W1.5 that consumes this, archive via `openspec archive add-ed25519-platform-crypto-impl --yes`
