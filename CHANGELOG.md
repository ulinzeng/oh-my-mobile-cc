# Changelog


## 2026-04-22T16:14:36+0800

- feat(w0): bootstrap KMP skeleton, OpenSpec capabilities, docs and SessionEnd hook
- chore: initial baseline — Plan v2 + .claude config + .gitignore

## 2026-04-23T10:10:52+0800

- feat(w1.1): add NDJSON framer (Flow<String> → complete lines → CCEvent)
- feat(w1.1): CCEvent + WireMessage protocol layer with round-trip + Unknown fallback
- docs(prp): add W1.1 plan — CCEvent + WireMessage round-trip
- docs(openspec): archive fix-approval-bridge-mechanism
- docs(openspec): propose fix-approval-bridge-mechanism (ADR-0004 codification)
- chore(w0-closeout): archive bootstrap, capture real CC stream, ADR-0004 for approval bridge
- feat(w0): bootstrap KMP skeleton, OpenSpec capabilities, docs and SessionEnd hook
- chore: initial baseline — Plan v2 + .claude config + .gitignore

## 2026-04-23T12:01:29+0800

- feat(w1.4): [red+green] relay-cli approval-bridge subcommand + entry router
- feat(w1.4): [red+green] BridgeServer + BridgeClient over UDS
- feat(w1.4): [red+green] ApprovalBridge orchestrator + store + RPC types
- docs(prp): add W1.4 plan — ApprovalBridge + relay-cli approval-bridge
- refactor(w1.3): [refactor] prune dead scope + redundant try/finally
- feat(w1.3): [red+green] writer + lifecycle tests for ClaudeProcess
- feat(w1.3): [red+green] ClaudeProcess replays NDJSON fixture end-to-end
- feat(w1.3): [red+green] InputStream.asLineFlow bridge
- test(w1.1): raise WireMessage coverage to 97.8% + refresh CODEMAPS/CHANGELOG
- feat(w1.1): add NDJSON framer (Flow<String> → complete lines → CCEvent)
- feat(w1.1): CCEvent + WireMessage protocol layer with round-trip + Unknown fallback
- docs(prp): add W1.1 plan — CCEvent + WireMessage round-trip
- docs(openspec): archive fix-approval-bridge-mechanism
- docs(openspec): propose fix-approval-bridge-mechanism (ADR-0004 codification)
- chore(w0-closeout): archive bootstrap, capture real CC stream, ADR-0004 for approval bridge
- feat(w0): bootstrap KMP skeleton, OpenSpec capabilities, docs and SessionEnd hook
- chore: initial baseline — Plan v2 + .claude config + .gitignore

## 2026-04-23T15:04:25+0800

- docs(w1.5): session handoff appendix — Tasks 0-7 done, 8-13 remain
- feat(w1.5): [red+green] NonceCache — LRU bound + 10-min TTL for replay defense
- feat(w1.5): [green] PairingService + 6-digit code + pubkey registry
- test(w1.5): [red] PairingCode + PairingService
- feat(w1.5): [red+green] hello.* WireMessage subtypes + DeviceId + HelloCodec
- feat(w1.5): [red+green] Base64Url + platform SecureRandom seam
- feat(w1.5): [green] Ed25519 via BouncyCastle (platform crypto per ADR-0005)
- docs(openspec): propose add-ed25519-platform-crypto-impl
- test(w1.5): [red] RFC 8032 Ed25519 test vectors
- chore(w1.5): wire ktor-server-test-host for pairing server tests
- docs(prp): add W1.5 plan — pairing + RelayClient (Ed25519 + WS transport)
- docs(w1.4): refresh CHANGELOG + CODEMAPS for W1.4 approval bridge
- feat(w1.4): [red+green] relay-cli approval-bridge subcommand + entry router
- feat(w1.4): [red+green] BridgeServer + BridgeClient over UDS
- feat(w1.4): [red+green] ApprovalBridge orchestrator + store + RPC types
- docs(prp): add W1.4 plan — ApprovalBridge + relay-cli approval-bridge
- refactor(w1.3): [refactor] prune dead scope + redundant try/finally
- feat(w1.3): [red+green] writer + lifecycle tests for ClaudeProcess
- feat(w1.3): [red+green] ClaudeProcess replays NDJSON fixture end-to-end
- feat(w1.3): [red+green] InputStream.asLineFlow bridge
- test(w1.1): raise WireMessage coverage to 97.8% + refresh CODEMAPS/CHANGELOG
- feat(w1.1): add NDJSON framer (Flow<String> → complete lines → CCEvent)
- feat(w1.1): CCEvent + WireMessage protocol layer with round-trip + Unknown fallback
- docs(prp): add W1.1 plan — CCEvent + WireMessage round-trip
- docs(openspec): archive fix-approval-bridge-mechanism
- docs(openspec): propose fix-approval-bridge-mechanism (ADR-0004 codification)
- chore(w0-closeout): archive bootstrap, capture real CC stream, ADR-0004 for approval bridge
- feat(w0): bootstrap KMP skeleton, OpenSpec capabilities, docs and SessionEnd hook
- chore: initial baseline — Plan v2 + .claude config + .gitignore

## 2026-04-23T17:23:29+0800

- chore(workflow): institutionalize ECC × superpowers × OpenSpec orchestration
- chore(plan): slim w1.5 plan.md to ≤300 lines + archive merged plans
- docs(openspec): propose add-w15-relay-transport-and-cli
- test(w1.5): [red] RelayServer WS handshake + single-connection
- test(w1.5): pin skew boundary — abs == 60_000ms must accept
- feat(w1.5): [green] ClientHelloVerifier — skew+nonce+registry+ed25519
- test(w1.5): [red] ClientHelloVerifier — skew/nonce/revoked/sig paths
- docs(w1.5): session handoff appendix — Tasks 0-7 done, 8-13 remain
- feat(w1.5): [red+green] NonceCache — LRU bound + 10-min TTL for replay defense
- feat(w1.5): [green] PairingService + 6-digit code + pubkey registry
- test(w1.5): [red] PairingCode + PairingService
- feat(w1.5): [red+green] hello.* WireMessage subtypes + DeviceId + HelloCodec
- feat(w1.5): [red+green] Base64Url + platform SecureRandom seam
- feat(w1.5): [green] Ed25519 via BouncyCastle (platform crypto per ADR-0005)
- docs(openspec): propose add-ed25519-platform-crypto-impl
- test(w1.5): [red] RFC 8032 Ed25519 test vectors
- chore(w1.5): wire ktor-server-test-host for pairing server tests
- docs(prp): add W1.5 plan — pairing + RelayClient (Ed25519 + WS transport)
- docs(w1.4): refresh CHANGELOG + CODEMAPS for W1.4 approval bridge
- feat(w1.4): [red+green] relay-cli approval-bridge subcommand + entry router
- feat(w1.4): [red+green] BridgeServer + BridgeClient over UDS
- feat(w1.4): [red+green] ApprovalBridge orchestrator + store + RPC types
- docs(prp): add W1.4 plan — ApprovalBridge + relay-cli approval-bridge
- refactor(w1.3): [refactor] prune dead scope + redundant try/finally
- feat(w1.3): [red+green] writer + lifecycle tests for ClaudeProcess
- feat(w1.3): [red+green] ClaudeProcess replays NDJSON fixture end-to-end
- feat(w1.3): [red+green] InputStream.asLineFlow bridge
- test(w1.1): raise WireMessage coverage to 97.8% + refresh CODEMAPS/CHANGELOG
- feat(w1.1): add NDJSON framer (Flow<String> → complete lines → CCEvent)
- feat(w1.1): CCEvent + WireMessage protocol layer with round-trip + Unknown fallback
- docs(prp): add W1.1 plan — CCEvent + WireMessage round-trip
- docs(openspec): archive fix-approval-bridge-mechanism
- docs(openspec): propose fix-approval-bridge-mechanism (ADR-0004 codification)
- chore(w0-closeout): archive bootstrap, capture real CC stream, ADR-0004 for approval bridge
- feat(w0): bootstrap KMP skeleton, OpenSpec capabilities, docs and SessionEnd hook
- chore: initial baseline — Plan v2 + .claude config + .gitignore
