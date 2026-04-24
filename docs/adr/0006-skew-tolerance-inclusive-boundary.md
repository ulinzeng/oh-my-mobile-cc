# ADR-0006: Skew 边界 `abs == 60_000ms` 接受（inclusive tolerance）

- **Status**: Accepted
- **Date**: 2026-04-24
- **Deciders**: 主 Claude + 用户
- **Relates to**: OpenSpec change `add-w15-relay-transport-and-cli`；capability `pairing`
- **Supersedes**: (none)

## Context

`openspec/specs/pairing/spec.md` 对 `ClientHello.timestampMs` 的 skew 规定
是 "客户端 timestamp 与 relay 系统时间差 **> ±60s**" 视为拒绝，错误
reason 归类 `skew`。W1.5 change proposal 沿用同样的文字。

问题：规格里写的是**严格大于** (`> 60s`)，那 "恰好等于 60_000ms" 的边界
值应该算**接受**还是**拒绝**？spec 没有在"= 60s 时如何"这一点上再细
化，实现和测试都必须对此做出一次性的决定，且后续所有 actual（relay /
client）都要照这个约定走，否则会出现"同一 ClientHello 在一台机器上过、
另一台不过"的非确定性。

W1.5 RED 阶段（commit `8130a43 test(w1.5): pin skew boundary — abs == 60_000ms must accept`）
已经把"边界 inclusive = 接受"作为事实写进测试：

- `positive skew at exactly 60s accepted`
- `negative skew at exactly 60s accepted`
- `positive skew over 60s rejected`（now = t − 60_001）
- `negative skew over 60s rejected`（now = t + 60_001）

`ClientHelloVerifier.verify` 的实现也明确：
```kotlin
if (abs(now - hello.timestampMs) > skewToleranceMs) return VerifyResult.Err("skew")
```
即 `>`（不是 `>=`），因此 `abs == 60_000ms` 落入接受侧。

本 ADR 把这件事从 session-local 约定升格为规范级承诺。

## Decision

- **Skew 边界 inclusive**：`|now − hello.timestampMs| ≤ skewToleranceMs`
  接受；`> skewToleranceMs` 拒绝。默认 `skewToleranceMs = 60_000ms`（即
  人肉读作 "±60 秒包含 60 秒"）。
- **实现侧写法统一为** `abs(diff) > tolerance` → reject（不得写
  `>=`；不得写 `abs(diff) <= tolerance` → accept 然后中途用 `else`
  拒掉 —— 一行 guard + 严格大于是唯一合规模式）。
- **测试契约**：任何 `ClientHelloVerifier` actual / mock / stub、以及
  移动端本地的 "客户端时钟自检" 实现，都**必须**覆盖四个边界用例：
  - `diff = +tolerance` → accept
  - `diff = −tolerance` → accept
  - `diff = +tolerance + 1` → reject（reason = `skew`）
  - `diff = −tolerance − 1` → reject（reason = `skew`）
- **spec 文字修订**：下一次 spec 触动（最迟在 W2 Ed25519 proposal archive
  结束前）把原句改为 "`timestamp` 与 relay 系统时间差**大于** ±60s 时
  拒绝（即允许等于 60s 的边界通过）"，以消除本 ADR 的 gap。

## Alternatives Considered

### A. Exclusive 边界（`abs >= tolerance` → reject）
- **理由**：一些安全审计对"边界算拒绝"更保守。
- **缺点**：与已经 commit 的 `8130a43` test 结果冲突；若改规则需要重跑
  RED 并翻动 `ClientHelloVerifier.verify`。而且规格的"大于 ±60s"字面
  更接近 inclusive。
- **结论**：拒绝。

### B. 把 tolerance 设成 `60_000 − 1ms`，边界问题消失
- **理由**：人为把边界挪到 `59_999`，让 `== 60_000` 永远落在拒绝侧，
  spec 侧不需要修。
- **缺点**：违反规格里 "±60s" 的字面数字；隐式让 tolerance 只剩
  59.999s，会让 clock 抖动到 ±60_000 的客户端在偶尔的 round-trip 下
  突然被拒。
- **结论**：拒绝。

### C. 直接把 skew tolerance 放宽到 ±120s 避开边界争议
- **理由**："大一点"永远好，边界不那么关键。
- **缺点**：与安全设计相违 —— replay-window 放宽会实质加大 `NonceCache`
  要守的时间窗。TTL 已经是 10 min，skew 再翻倍没有收益。
- **结论**：拒绝。

## Consequences

### 正面
- **单一事实源**：所有 actual / 测试 / client / relay 对 "60_000ms 边
  界" 行为一致，不会再出现 session-local 的"本地 OK 远端拒"。
- **实现约束简单**：`abs(diff) > tolerance` 一行 guard，readable，
  easy-to-review，并且已经在 `8130a43 + 39a836f` 两次 commit 里落地。
- **Test coverage 门** 和 **spec 字面** 在下次 spec 更新后同步，消除
  后续 reviewer 疑问。

### 代价
- **`spec.md` 文字需要在下一次 spec 触动时做一次修订**（"大于 ±60s"
  → "大于 ±60s，等于 ±60s 仍接受"）。W1.5 archive 时可以考虑顺手带
  上；不带的话最迟在 Ed25519 proposal archive 时补。
- **客户端侧**：需要知道"自己生成的 timestamp 不能早于 `now() −
  60_000ms`，否则一来一回就 out"。这条已经在 `KtorRelayClient` 的
  `sendClientHello` 里通过 `clock()` 即时取当前时间解决；自定义 clock
  seam 的调用方要注意不要传入 stale 时间。

### 中性
- 本 ADR 不改动 `NonceCache` 的 10 min TTL。两者独立：skew 管"时间
  戳差"，nonce 管"是否重复使用"。

## References

- RFC 8032（Ed25519 本身不定义 skew）—  <https://www.rfc-editor.org/rfc/rfc8032>
- OpenSpec change: `openspec/changes/add-w15-relay-transport-and-cli/`
- 相关 spec: `openspec/specs/pairing/spec.md` §Ed25519 会话签名
- 相关实现: `relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/ClientHelloVerifier.kt`
- 相关测试: `relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/ClientHelloVerifierTest.kt`
  （commit `8130a43` + `39a836f`）
- 相关 plan: `.claude/PRPs/plans/w1.5-pairing-relayclient.plan.md`
