package io.ohmymobilecc.relay.approval

import io.ohmymobilecc.core.protocol.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the UNIX-domain-socket transport that carries a single
 * [BridgeRequest] from the spawned `relay-cli approval-bridge`
 * subprocess to the main relay and a single [BridgeDecision] back.
 *
 * We plug a fake [ApprovalBridge] that returns a canned ALLOW so the
 * test focuses on wire behavior, not orchestration.
 */
class BridgeServerClientTest {
    private lateinit var socketPath: Path
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        // macOS caps sun_path at 104 bytes — the system tmp dir
        // (/var/folders/.../T/...) easily blows past that. Use /tmp
        // directly with a short name.
        val dir = Files.createTempDirectory(Path.of("/tmp"), "ohmmcc-")
        socketPath = dir.resolve("r.sock")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(socketPath.parent) }
    }

    @Test
    fun `happy path — client dials server and receives decision`() =
        runBlocking {
            val bridge = cannedAllowBridge()
            val server = BridgeServer(socketPath, bridge, scope)
            server.start()

            val decision =
                withTimeout(TEST_TIMEOUT_MS) {
                    BridgeClient.request(
                        socketPath = socketPath,
                        request = sampleRequest(),
                    )
                }

            assertEquals("allow", decision.permissionDecision)
            assertEquals("auto-allowed by policy", decision.permissionDecisionReason)

            server.close()
        }

    @Test
    fun `server cleans up a stale socket file on start`() =
        runBlocking {
            // Pre-create a stale file at the socket path.
            Files.createFile(socketPath)
            assertTrue(socketPath.exists(), "precondition: stale file exists")

            val bridge = cannedAllowBridge()
            val server = BridgeServer(socketPath, bridge, scope)
            server.start() // must NOT throw "address already in use"

            val decision =
                withTimeout(TEST_TIMEOUT_MS) {
                    BridgeClient.request(socketPath, sampleRequest())
                }
            assertEquals("allow", decision.permissionDecision)

            server.close()
        }

    @Test
    fun `client fails loudly when no server is bound`() =
        runBlocking {
            // No server started — socket path doesn't exist.
            assertFailsWith<IOException> {
                BridgeClient.request(socketPath, sampleRequest())
            }
        }

    private fun cannedAllowBridge(): ApprovalBridge {
        val store = InMemoryApprovalStore()
        val outbound = MutableSharedFlow<WireMessage>(replay = 8)
        // Pre-register the policy so any request short-circuits with AUTO_ALLOWED.
        val bridge = ApprovalBridge(store = store, outbound = outbound, scope = scope)
        runBlocking { store.upsertPolicy("Bash", "S1") }
        return bridge
    }

    private fun sampleRequest(): BridgeRequest =
        BridgeRequest(
            sessionId = "S1",
            toolName = "Bash",
            toolUseId = "T1",
            toolInput = buildJsonObject { put("command", JsonPrimitive("ls")) },
            hookPayload = buildJsonObject { put("tool_use_id", JsonPrimitive("T1")) },
        )

    private companion object {
        const val TEST_TIMEOUT_MS: Long = 5_000
    }
}
