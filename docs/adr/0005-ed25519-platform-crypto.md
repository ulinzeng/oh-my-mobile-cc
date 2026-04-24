---
status: accepted
date: 2026-04-23
depends-on: []
---

# ADR-0005: Ed25519 via platform crypto (BouncyCastle on JVM/Android, stub on iOS)

- **Status**: Accepted
- **Date**: 2026-04-23
- **Deciders**: 主 Claude + 用户
- **Relates to**: OpenSpec change `add-ed25519-platform-crypto-impl`；capability `pairing`
- **Supersedes**: (none) — 此前 Plan v2 的"手写 pure-Kotlin RFC 8032 port"提案

## Context

`openspec/specs/pairing/spec.md` 要求移动端用 Ed25519 为每次 WS `ClientHello`
签名，relay 做 verify；失败 / skew / nonce 重放都要拒。规格本身对**如何实现
Ed25519**没有约束。

W1.5 初稿计划直接从 RFC 8032 §6 参考代码手写一份 ~400 LOC 的 pure-Kotlin
Ed25519（field 算术、scalar 乘法、Edwards 点运算 + 内置 SHA-512）。评审中
发现这条路风险/收益失衡：

1. **审计负担**：field 算术、constant-time 比较、carry propagation 任何一个
   环节出错都会在没有报错的前提下破坏签名；reviewer 没有能力每次 PR 逐行
   复核。
2. **旁路风险**：RFC 参考实现是"正确性优先"，不是 timing-safe；照抄容易留
   下侧信道。
3. **目标平台都已有可用的成熟实现**：JVM/Android 有 BouncyCastle（20 年老
   牌、多次审计），iOS 有 CryptoKit。全部用 `expect`/`actual` 拼装只需
   ~50 LOC。

## Decision

- **JVM + Android**：使用 **BouncyCastle 1.78+**（`org.bouncycastle:bcprov-jdk18on`）
  的 `Ed25519Signer` + `Ed25519PrivateKeyParameters` / `Ed25519PublicKeyParameters`。
- **iOS**：**W1.5 出 stub**，三方法都 `throw NotImplementedError(...)`，消息
  指向将来的 change id `add-ios-ed25519-actual`（W2.1 要落地 iOS 客户端时
  再开 proposal 决定 CryptoKit vs 固定 C 库 vs 其他）。
- **禁令**：`shared/core/crypto/` 下不得出现 `FieldElement` / `Scalar` /
  `EdwardsPoint` 这类手写 field arithmetic 的 pure-Kotlin 重实现。任何此
  类 PR SHALL 被 review 拒绝，除非有新的 OpenSpec proposal 明确解除本
  约束。
- **契约**：每个 actual 都必须通过 RFC 8032 §7.1 Vector 1 + Vector 2（位对
  位）；contract test 放在 `shared/src/commonTest/` 下，对每个激活的
  target 运行。iOS actual 未实现前，只在 JVM 目标上运行（W2.1 随 iOS
  actual 落地时补齐 iOS test source set 执行入口）。

## Alternatives Considered

### A. Pure-Kotlin RFC 8032 port（~400 LOC）
- **优点**：零外部依赖；KMP 原生（`commonMain`）。
- **缺点**：审计负担沉重；timing-safe 难以保证；4 处 corner case（carry
  propagation / 非标准 R / S ≥ ℓ / canonical encoding）全部需要重测。
- **结论**：拒绝。

### B. libsodium via JNI（例如 `lazysodium-kmp`）
- **优点**：C 库成熟；签名快。
- **缺点**：引入 native 工具链（iOS 要求 CMake，Android 要求 NDK）；
  `lazysodium-kmp` 维护频率不如 BC；移动侧体积 + cold-start 成本。
- **结论**：拒绝——收益不足以覆盖 native build pipeline 代价。

### C. JDK 15+ / Android API 34+ 原生 `Signature("Ed25519")`
- **优点**：零外部依赖，JDK/平台已带。
- **缺点**：Android 我们 `minSdk = 26`，需要到 34 才有；过渡阶段还得挂 BC
  兜底。与其维护两个 Android actual 不如先一律走 BC，等 `minSdk` 升到
  34 再做一次"swap"（新 proposal）。
- **结论**：当前不采用；记录为未来迁移路径。

### D. `tink-kmp` / `tink-android`
- **优点**：Google 维护，API 简洁。
- **缺点**：体积比 BC 大；KMP 官方支持仍在迭代；社区积累不如 BC 深。
- **结论**：拒绝——非核心理由，主要是 BC 已足够且更稳定。

## Consequences

### 正面
- **安全风险显著降低**：Ed25519 原语不再由本项目自行维护，出漏洞的可能
  性转移给 BC 上游。
- **实现速度**：W1.5 节省约 1–2 天 Ed25519 专项工作，腾出来给 pairing
  flow、RelayServer、KtorRelayClient 等 glue code。
- **RFC 8032 contract test** 把"将来换 actual"的风险兜住：任何 actual
  替换都必须过这两个 vector。

### 代价
- **Android APK 尺寸**：BC 完整 bcprov-jdk18on 约 6 MB。W2 Android 打 AAB
  时开 `minifyEnabled true` + R8 shrink 后，只保留 Ed25519 相关类（约 200 KB
  估算）。ProGuard 规则作为 W2 Android shell 的任务之一补充：
  ```
  -keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
  -keep class org.bouncycastle.crypto.params.Ed25519* { *; }
  ```
- **iOS W1.5 gap**：iOS 的签名能力 W1.5 还不可用。接受——W1.5 主要打磨 relay
  侧 + JVM shared，iOS 端到端走到 W2。

### 中性
- `Ed25519KeyPair.secretKey` 统一 64 字节 `seed || publicKey`（RFC §5.1.5），
  所有 actual 都照此 layout，因此 key 可以跨 actual 流转（未来切 CryptoKit
  / 原生 Android 也不用迁移 key 存储）。

## Security Notes

- BC 的 `Ed25519Signer` 是 constant-time 实现（上游文档 + 源码证实）。
- Public key 由 seed 推导（`Ed25519PrivateKeyParameters(seed, 0).generatePublicKey()`），
  与 RFC §5.1.5 完全一致；`Ed25519Test` 通过 byte-for-byte 比对确认。
- `verify` 返回 `false` 不抛异常（符合 spec：签名错误 = 返回 false，不要
  throw 泄露更多信息）；输入长度异常（`publicKey != 32 bytes` /
  `signature != 64 bytes`）同样走 `return false`，不 throw。

## Migration Path (future)

当 `minSdk` 升到 34 时：

1. 开新 proposal `switch-android-ed25519-to-platform`。
2. `shared/src/androidMain/kotlin/.../Ed25519.kt` 改用 `java.security.Signature("Ed25519")`。
3. contract test 覆盖 Android target（通过 instrumented test 或 Robolectric）。
4. 视体积收益决定是否同步从 `androidMain` 里移除 BC。

## References

- RFC 8032 §5 / §7.1 — <https://www.rfc-editor.org/rfc/rfc8032>
- BouncyCastle: <https://www.bouncycastle.org/documentation.html>
- OpenSpec change: `openspec/changes/add-ed25519-platform-crypto-impl/`
- 相关 spec: `openspec/specs/pairing/spec.md`
- 相关 plan: `.claude/PRPs/plans/w1.5-pairing-relayclient.plan.md`
