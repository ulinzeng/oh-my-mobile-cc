package io.ohmymobilecc.relay.cli

import io.ohmymobilecc.core.protocol.ProtocolJson
import io.ohmymobilecc.core.protocol.WireMessage
import io.ohmymobilecc.relay.approval.ApprovalBridge
import io.ohmymobilecc.relay.approval.BridgeServer
import io.ohmymobilecc.relay.approval.InMemoryApprovalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [ApprovalBridgeCommand] end-to-end in-process by swapping
 * stdin/stdout/stderr for byte buffers. Avoids the JVM-spawn cost of
 * a true subprocess test but still exercises the full parse → dial →
 * emit flow.
 *
 * A real subprocess smoke test lives in
 * [io.ohmymobilecc.relay.claude.ClaudeProcess]-adjacent integration
 * files (deferred to W1.5 end-to-end runs). For now in-process is
 * more than enough to gate the CC hook contract.
 */
class ApprovalBridgeCommandTest {
    private lateinit var socketPath: Path
    private lateinit var scope: CoroutineScope
    private lateinit var server: BridgeServer

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory(Path.of("/tmp"), "ohmmcc-")
        socketPath = dir.resolve("r.sock")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val store = InMemoryApprovalStore()
        runBlocking { store.upsertPolicy("Bash", "S1") } // auto-allow for determinism
        val outbound = MutableSharedFlow<WireMessage>(replay = 8)
        val bridge = ApprovalBridge(store = store, outbound = outbound, scope = scope)
        server = BridgeServer(socketPath, bridge, scope)
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        scope.cancel()
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(socketPath.parent) }
    }

    @Test
    fun `prints CC-format decision JSON on stdout with exit 0`() {
        val hookPayload =
            buildJsonObject {
                put("session_id", JsonPrimitive("S1"))
                put("tool_name", JsonPrimitive("Bash"))
                put("tool_use_id", JsonPrimitive("T1"))
                put(
                    "tool_input",
                    buildJsonObject {
                        put("command", JsonPrimitive("ls"))
                    },
                )
            }
        val stdin = ByteArrayInputStream(hookPayload.toString().toByteArray(Charsets.UTF_8))
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit =
            ApprovalBridgeCommand.run(
                argv = arrayOf("--session-id", "S1", "--socket", socketPath.toString()),
                stdin = stdin,
                stdout = PrintStream(stdout, true, Charsets.UTF_8),
                stderr = PrintStream(stderr, true, Charsets.UTF_8),
            )

        assertEquals(0, exit, "expected exit 0, stderr=${stderr.toString(Charsets.UTF_8)}")
        val line = stdout.toString(Charsets.UTF_8).trim()
        val parsed = ProtocolJson.default.parseToJsonElement(line).jsonObject
        val hookSpecific = parsed["hookSpecificOutput"]?.jsonObject
        assertTrue(hookSpecific != null, "missing hookSpecificOutput: $line")
        assertEquals("PreToolUse", hookSpecific["hookEventName"]?.jsonPrimitive?.content)
        assertEquals("allow", hookSpecific["permissionDecision"]?.jsonPrimitive?.content)
    }

    @Test
    fun `exits non-zero when bridge socket is unreachable`() {
        server.close() // kill the server, leave socket path dangling
        runCatching { Files.deleteIfExists(socketPath) }

        val stdin =
            ByteArrayInputStream(
                """{"session_id":"S1","tool_name":"Bash","tool_use_id":"T1","tool_input":{}}"""
                    .toByteArray(Charsets.UTF_8),
            )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit =
            ApprovalBridgeCommand.run(
                argv = arrayOf("--session-id", "S1", "--socket", socketPath.toString()),
                stdin = stdin,
                stdout = PrintStream(stdout, true, Charsets.UTF_8),
                stderr = PrintStream(stderr, true, Charsets.UTF_8),
            )

        assertEquals(2, exit)
        assertTrue(
            stderr.toString(Charsets.UTF_8).isNotBlank(),
            "expected stderr diagnostic on IPC failure",
        )
    }

    @Test
    fun `exits non-zero when hook payload is malformed JSON`() {
        val stdin = ByteArrayInputStream("this is not json".toByteArray(Charsets.UTF_8))
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit =
            ApprovalBridgeCommand.run(
                argv = arrayOf("--session-id", "S1", "--socket", socketPath.toString()),
                stdin = stdin,
                stdout = PrintStream(stdout, true, Charsets.UTF_8),
                stderr = PrintStream(stderr, true, Charsets.UTF_8),
            )

        assertEquals(2, exit)
    }

    @Test
    fun `RelayCli dispatch routes approval-bridge subcommand`() {
        // Smoke: dispatch with leading arg must delegate to the command.
        val hookPayload: JsonObject =
            buildJsonObject {
                put("session_id", JsonPrimitive("S1"))
                put("tool_name", JsonPrimitive("Bash"))
                put("tool_use_id", JsonPrimitive("T1"))
                put("tool_input", buildJsonObject { put("command", JsonPrimitive("ls")) })
            }
        val stdin = ByteArrayInputStream(hookPayload.toString().toByteArray(Charsets.UTF_8))
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit =
            RelayCli.dispatch(
                argv = arrayOf("approval-bridge", "--session-id", "S1", "--socket", socketPath.toString()),
                stdin = stdin,
                stdout = PrintStream(stdout, true, Charsets.UTF_8),
                stderr = PrintStream(stderr, true, Charsets.UTF_8),
            )

        assertEquals(0, exit, "dispatch stderr=${stderr.toString(Charsets.UTF_8)}")
        assertTrue(stdout.toString(Charsets.UTF_8).contains("hookSpecificOutput"))
    }
}
