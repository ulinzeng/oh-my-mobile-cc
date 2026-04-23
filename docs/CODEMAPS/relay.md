# CODEMAP: relay

_Generated 2026-04-23T17:23:29+0800 by .claude/scripts/gen-codemaps.sh_

Source root: `relay/src`

## Kotlin files

- relay/src/main/kotlin/io/ohmymobilecc/relay/Main.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/approval/ApprovalBridge.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/approval/ApprovalStore.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/approval/BridgeRpc.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/approval/BridgeServerClient.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/approval/InMemoryApprovalStore.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/approval/TimeSeam.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/claude/ClaudeInput.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/claude/ClaudeProcess.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/claude/InputStreamFlow.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/cli/ApprovalBridgeCommand.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/cli/RelayCli.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/ClientHelloVerifier.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/ClockSeam.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/NonceCache.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/PairingCode.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/PairingService.kt
- relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/PubkeyRegistry.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/approval/ApprovalBridgeTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/approval/BridgeRpcTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/approval/BridgeServerClientTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/approval/InMemoryApprovalStoreTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/claude/ClaudeProcessLifecycleTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/claude/ClaudeProcessTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/claude/ClaudeProcessWriteTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/claude/InputStreamFlowTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/cli/ApprovalBridgeCommandSubprocessTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/cli/ApprovalBridgeCommandTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/ClientHelloVerifierTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/NonceCacheTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/PairingCodeTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/PairingServiceTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/server/RelayServerTest.kt
- relay/src/test/kotlin/io/ohmymobilecc/relay/server/SingleConnectionRegistryTest.kt

## Public top-level declarations (best-effort)

    relay/src/main/kotlin/io/ohmymobilecc/relay/Main.kt:fun main(args: Array<String>) {
    relay/src/main/kotlin/io/ohmymobilecc/relay/approval/ApprovalBridge.kt:public class ApprovalBridge(
    relay/src/main/kotlin/io/ohmymobilecc/relay/approval/ApprovalStore.kt:public interface ApprovalStore {
    relay/src/main/kotlin/io/ohmymobilecc/relay/approval/BridgeServerClient.kt:public class BridgeServer(
    relay/src/main/kotlin/io/ohmymobilecc/relay/approval/BridgeServerClient.kt:public object BridgeClient {
    relay/src/main/kotlin/io/ohmymobilecc/relay/approval/InMemoryApprovalStore.kt:public class InMemoryApprovalStore : ApprovalStore {
    relay/src/main/kotlin/io/ohmymobilecc/relay/approval/TimeSeam.kt:public interface TimeSeam {
    relay/src/main/kotlin/io/ohmymobilecc/relay/approval/TimeSeam.kt:public object SystemTimeSeam : TimeSeam {
    relay/src/main/kotlin/io/ohmymobilecc/relay/claude/ClaudeProcess.kt:public class ClaudeProcess(
    relay/src/main/kotlin/io/ohmymobilecc/relay/claude/InputStreamFlow.kt:internal fun InputStream.asChunkedCharFlow(): Flow<String> {
    relay/src/main/kotlin/io/ohmymobilecc/relay/claude/InputStreamFlow.kt:public fun InputStream.asLineFlow(): Flow<String> = asChunkedCharFlow().ndjsonLines().flowOn(Dispatchers.IO)
    relay/src/main/kotlin/io/ohmymobilecc/relay/cli/ApprovalBridgeCommand.kt:public object ApprovalBridgeCommand {
    relay/src/main/kotlin/io/ohmymobilecc/relay/cli/RelayCli.kt:public object RelayCli {
    relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/ClientHelloVerifier.kt:public class ClientHelloVerifier(
    relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/ClockSeam.kt:public interface ClockSeam {
    relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/ClockSeam.kt:public object SystemClockSeam : ClockSeam {
    relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/NonceCache.kt:public class NonceCache(
    relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/PairingService.kt:public class PairingService(
    relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/PubkeyRegistry.kt:public class InMemoryPubkeyRegistry : PubkeyRegistry {
    relay/src/main/kotlin/io/ohmymobilecc/relay/pairing/PubkeyRegistry.kt:public interface PubkeyRegistry {
    relay/src/test/kotlin/io/ohmymobilecc/relay/approval/ApprovalBridgeTest.kt:class ApprovalBridgeTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/approval/BridgeRpcTest.kt:class BridgeRpcTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/approval/BridgeServerClientTest.kt:class BridgeServerClientTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/approval/InMemoryApprovalStoreTest.kt:class InMemoryApprovalStoreTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/claude/ClaudeProcessLifecycleTest.kt:class ClaudeProcessLifecycleTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/claude/ClaudeProcessTest.kt:class ClaudeProcessTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/claude/ClaudeProcessWriteTest.kt:class ClaudeProcessWriteTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/claude/InputStreamFlowTest.kt:class InputStreamFlowTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/cli/ApprovalBridgeCommandSubprocessTest.kt:class ApprovalBridgeCommandSubprocessTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/cli/ApprovalBridgeCommandTest.kt:class ApprovalBridgeCommandTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/ClientHelloVerifierTest.kt:class ClientHelloVerifierTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/NonceCacheTest.kt:class NonceCacheTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/PairingCodeTest.kt:class PairingCodeTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/PairingServiceTest.kt:class PairingServiceTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/PairingServiceTest.kt:internal class FakeClock(
    relay/src/test/kotlin/io/ohmymobilecc/relay/pairing/PairingServiceTest.kt:internal class FakeRandom(
    relay/src/test/kotlin/io/ohmymobilecc/relay/server/RelayServerTest.kt:class RelayServerTest {
    relay/src/test/kotlin/io/ohmymobilecc/relay/server/SingleConnectionRegistryTest.kt:class SingleConnectionRegistryTest {
