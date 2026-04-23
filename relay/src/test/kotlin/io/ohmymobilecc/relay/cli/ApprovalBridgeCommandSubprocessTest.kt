package io.ohmymobilecc.relay.cli

import io.ohmymobilecc.core.protocol.WireMessage
import io.ohmymobilecc.relay.approval.ApprovalBridge
import io.ohmymobilecc.relay.approval.BridgeServer
import io.ohmymobilecc.relay.approval.InMemoryApprovalStore
import io.ohmymobilecc.relay.claude.ClaudeProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * End-to-end smoke test that spawns a real JVM subprocess running the
 * relay jar's `approval-bridge` subcommand and exercises the full
 * path a CC `PreToolUse` hook would take in production.
 *
 * Slower than [ApprovalBridgeCommandTest] because it goes through JVM
 * startup, but catches packaging regressions (shadow jar routing,
 * classpath misses, Main.kt dispatch) that the in-process test can't
 * see.
 */
class ApprovalBridgeCommandSubprocessTest {
    private lateinit var socketPath: Path
    private lateinit var scope: CoroutineScope
    private lateinit var server: BridgeServer

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory(Path.of("/tmp"), "ohmmcc-")
        socketPath = dir.resolve("r.sock")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val store = InMemoryApprovalStore()
        runBlocking { store.upsertPolicy("Bash", "S1") }
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
    fun `real JVM subprocess prints CC-format decision JSON`() =
        runBlocking {
            val java = System.getProperty("java.home") + "/bin/java"
            val classpath = System.getProperty("java.class.path")
            val child =
                ClaudeProcess(
                    command =
                        listOf(
                            java,
                            "-cp",
                            classpath,
                            "io.ohmymobilecc.relay.MainKt",
                            "approval-bridge",
                            "--session-id",
                            "S1",
                            "--socket",
                            socketPath.toString(),
                        ),
                    workingDir = Path.of(System.getProperty("user.dir")),
                )

            // Feed the hook payload via stdin. ClaudeInput.UserMessage
            // would nest under {"message":...}; here we want a plain
            // JSON object, so bypass writeUserMessage and use the raw
            // process stdin through a small helper stored below.
            writeRaw(
                child,
                """{"session_id":"S1","tool_name":"Bash","tool_use_id":"T1","tool_input":{"command":"ls"}}""",
            )
            child.closeStdin()

            val exit =
                withTimeout(SUBPROCESS_TIMEOUT_MS) {
                    child.exit.await()
                }
            assertEquals(0, exit, "subprocess exited non-zero")

            // Stdout must contain exactly one JSON line with
            // hookSpecificOutput.permissionDecision=allow.
            val firstEvent =
                withTimeout(SUBPROCESS_TIMEOUT_MS) {
                    // `ccEvents()` will return Unknown for our envelope
                    // (no "type" discriminator) — that's fine; the raw
                    // JSON is preserved.
                    child.events.toList().firstOrNull()
                        ?: fail("subprocess printed no stdout")
                }
            val envelope = firstEvent.raw.jsonObject
            val hookSpecific = envelope["hookSpecificOutput"]?.jsonObject ?: fail("missing hookSpecificOutput")
            assertEquals("PreToolUse", hookSpecific["hookEventName"]?.jsonPrimitive?.content)
            assertEquals("allow", hookSpecific["permissionDecision"]?.jsonPrimitive?.content)
        }

    /**
     * Writes a raw (pre-framed) JSON line to the child's stdin. We
     * intentionally don't reuse `writeUserMessage` because that wraps
     * the payload in the stream-json `{"type":"user","message":{...}}`
     * envelope — the hook subcommand expects a bare hook payload
     * object on stdin.
     */
    private fun writeRaw(
        child: ClaudeProcess,
        json: String,
    ) {
        // Use reflection-free access: ClaudeProcess doesn't currently
        // expose the raw outputStream, so we piggy-back on
        // writeUserMessage's internals by constructing a fake
        // UserMessage whose serialized form matches what we want.
        // Simpler: poke the process.outputStream via a side door —
        // but there isn't one. For now, we hack around it by writing
        // a benign UserMessage and immediately writing the real
        // payload through the same writer. Cleaner fix: add a
        // `writeRaw` to ClaudeProcess — tracked as a TODO for W1.5
        // because this is a test-only concern.
        val field = child.javaClass.getDeclaredField("process").apply { isAccessible = true }
        val process = field.get(child) as Process
        process.outputStream.write((json + "\n").toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    private companion object {
        const val SUBPROCESS_TIMEOUT_MS: Long = 15_000
    }
}
