## 1. RelayServer GREEN (W1.5 plan Task 11)

**Depends on**: Task 10 RED (already committed as `98fde66`).

- [x] 1.1 Task 10 RED 已提交(`test(w1.5): [red] RelayServer WS handshake + single-connection`)
- [ ] 1.2 Create `relay/src/main/kotlin/io/ohmymobilecc/relay/server/SingleConnectionRegistry.kt` — token-based claim/release per D1
- [ ] 1.3 Create `relay/src/main/kotlin/io/ohmymobilecc/relay/server/RelayServer.kt` — `object RelayServer { fun install(app, registry, nonceCache, clock, outbound, onInbound) }`
- [ ] 1.4 First-frame dispatch: parse `Frame.Text` → `WireMessage`. Missing / malformed → `HelloErr(malformed)` + close 1007. Non-ClientHello → `HelloErr(expected-hello)` + close 1008. ClientHello → pass to `ClientHelloVerifier`. `VerifyResult.Err(reason)` → `HelloErr(reason)` + close 1008 (all verifier reasons). `VerifyResult.Ok(deviceId)` → claim slot via `SingleConnectionRegistry`. If claim fails → `HelloErr(duplicate-session)` + close 1013. Else → send `HelloOk(serverTimeMs = clock.nowMs(), protocolVersion = 1)`.
- [ ] 1.5 Plumb `outbound: Flow<WireMessage>` → WS outgoing with `launch { outbound.collect { session.send(Frame.Text(ProtocolJson.default.encodeToString(it))) } }`. Install `session.coroutineContext`-scoped cancellation on disconnect.
- [ ] 1.6 Plumb WS incoming → `onInbound: suspend (WireMessage) -> Unit` callback; parse Frame.Text → WireMessage; unparseable frames ignored with warn log (do NOT close connection — post-hello malformed frames are non-fatal per existing "传输语义")
- [ ] 1.7 Release slot on disconnect (both sides) via `SingleConnectionRegistry.release(sessionId, token)`
- [ ] 1.8 Run `./gradlew :relay:test --tests "*SingleConnectionRegistryTest" --tests "*RelayServerTest"` — 3 + 3 cases pass
- [ ] 1.9 Run `:relay:ktlintCheck :relay:detekt` — green
- [ ] 1.10 Commit `feat(w1.5): [green] RelayServer + SingleConnectionRegistry`

## 2. TransportPort + KtorRelayClient (W1.5 plan Task 12)

- [ ] 2.1 Write `shared/src/commonTest/.../transport/KtorRelayClientTest.kt` (RED) — runs against a real Ktor Netty engine on a free port with a minimal test relay that echoes HelloOk / emits a pushed ApprovalRequested. Cases: happy path, `HelloErr(revoked)` → `Result.failure(RelayError.Rejected("revoked"))`, protocol-violation after connect.
- [ ] 2.2 Run test → RED
- [ ] 2.3 Commit `test(w1.5): [red] KtorRelayClient transport contract`
- [ ] 2.4 Create `shared/src/commonMain/kotlin/io/ohmymobilecc/core/transport/` — `TransportPort.kt`, `TransportSession.kt`, `TransportEndpoint.kt`, `DeviceIdentity.kt`, `RelayError.kt`
- [ ] 2.5 Create `shared/src/jvmMain/kotlin/io/ohmymobilecc/core/transport/KtorRelayClient.kt` — `class KtorRelayClient(httpClient: HttpClient, clock: () -> Long) : TransportPort`. Generate nonce via `RandomSource` (common), sign via `Ed25519.sign`, await HelloOk/HelloErr, emit `TransportSession` wrapping the WS session.
- [ ] 2.6 Wire `shared/build.gradle.kts` `jvmMain.dependencies`: add `libs.ktor.client.cio` + `libs.ktor.client.websockets` (already aliased in libs.versions.toml).
- [ ] 2.7 Run `:shared:jvmTest` — GREEN
- [ ] 2.8 Run `:shared:ktlintCheck :shared:detekt` — GREEN
- [ ] 2.9 Commit `feat(w1.5): [green] shared TransportPort + KtorRelayClient jvm actual`

## 3. relay-cli pair / revoke / serve (W1.5 plan Task 13)

- [ ] 3.1 Write `relay/src/test/kotlin/io/ohmymobilecc/relay/cli/PairCommandTest.kt` + `RevokeCommandTest.kt` + `ServeCommandBootTest.kt` (RED). `PairCommandTest`: 6-digit stdout + timeout path. `RevokeCommandTest`: missing-id warning + happy path. `ServeCommandBootTest`: startup prints "listening on :<port>" + SIGINT-equivalent graceful stop.
- [ ] 3.2 Run → RED
- [ ] 3.3 Commit `test(w1.5): [red] relay-cli pair/revoke/serve`
- [ ] 3.4 Create `relay/src/main/kotlin/io/ohmymobilecc/relay/cli/PairCommand.kt` — blocks waiting on a pair-flow `Deferred<Ed25519 pubkey>`; 5-min timeout via `withTimeoutOrNull`.
- [ ] 3.5 Create `relay/src/main/kotlin/io/ohmymobilecc/relay/cli/RevokeCommand.kt`.
- [ ] 3.6 Create `relay/src/main/kotlin/io/ohmymobilecc/relay/cli/ServeCommand.kt` — starts Netty engine on `--port` / `RELAY_PORT` / 48964 default; installs `RelayServer`; wires `ApprovalBridge.outbound` + `onInbound = { if (it is WireMessage.ApprovalResponded) bridge.submitDecision(it) }`.
- [ ] 3.7 Update `relay/src/main/kotlin/io/ohmymobilecc/relay/cli/RelayCli.kt` — dispatch `pair` / `revoke` / `serve` in addition to the existing `approval-bridge`.
- [ ] 3.8 Wire CLI state:一个 `RelayProcessState` object that lazily constructs `InMemoryPubkeyRegistry` + `NonceCache` + `ApprovalBridge` **once** per JVM process so `pair` → `serve` in-process tests see consistent state. (persistent SqlDelight adapter lands in W2.3.)
- [ ] 3.9 Run `./gradlew :relay:test :relay:ktlintCheck :relay:detekt` — green
- [ ] 3.10 Commit `feat(w1.5): [green] relay-cli pair/revoke/serve + CLI shared state`

## 4. Coverage gate

- [ ] 4.1 Add / update `relay/build.gradle.kts` `koverVerify` rules:
  - `relay.pairing.**` line-coverage ≥ 85%
  - `relay.server.**` line-coverage ≥ 85%
  - `relay.cli.**` line-coverage ≥ 80%
- [ ] 4.2 Run `./gradlew :relay:koverVerify` — green
- [ ] 4.3 Commit `chore(w1.5): enforce koverVerify thresholds for pairing/server/cli`

## 5. Validation + archive prep

- [ ] 5.1 `openspec validate add-w15-relay-transport-and-cli --strict` — green
- [ ] 5.2 `./gradlew :shared:jvmTest :relay:test :relay:ktlintCheck :relay:detekt :shared:ktlintCheck :shared:detekt :shared:compileKotlinIosSimulatorArm64` — full green
- [ ] 5.3 Update CHANGELOG `## [Unreleased]` with Task 11/12/13 summary
- [ ] 5.4 Merge `feat/w1.5-pairing-relayclient` → main via `--no-ff` (matches W1.1/1.3/1.4 pattern)
- [ ] 5.5 `openspec archive add-w15-relay-transport-and-cli --yes` (after merge to main)
- [ ] 5.6 `openspec archive add-ed25519-platform-crypto-impl --yes` (that change's 7.4 task — archive it AFTER 5.5 so the archive order reflects dep order)
- [ ] 5.7 Delete `feat/w1.5-pairing-relayclient` local + origin
