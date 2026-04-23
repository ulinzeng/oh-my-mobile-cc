package io.ohmymobilecc.relay.claude

import io.ohmymobilecc.core.protocol.CCEvent
import io.ohmymobilecc.core.protocol.ProtocolJson
import io.ohmymobilecc.core.protocol.ccEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps a child `claude -p --output-format stream-json
 * --input-format stream-json` process (or, in tests, any NDJSON-emitting
 * binary) and exposes it as structured coroutine primitives:
 *
 *  - [events]  : `Flow<CCEvent>`    — stdout decoded as NDJSON
 *  - [stderr]  : `Flow<String>`     — raw stderr lines, kept out-of-band
 *  - [exit]    : `Deferred<Int>`    — completes exactly once with the
 *                child's exit code
 *  - [writeUserMessage] — serialize a single `{"type":"user",...}`
 *                stream-json frame to stdin, `\n` + flush. Protocol spec
 *                forbids writing permission responses through stdin, so
 *                the API accepts a sealed [ClaudeInput] type whose only
 *                concrete variant is [ClaudeInput.UserMessage].
 *
 * ### Lifecycle
 *
 * The child process is spawned in the constructor. Callers that want
 * to block for exit await [exit]. [close] is idempotent; after the
 * first call, subsequent [writeUserMessage] calls raise [IOException].
 *
 * ### Environment hygiene
 *
 * The caller supplies the full environment map; [ProcessBuilder]'s
 * inherited environment is cleared first. This keeps stray
 * `CLAUDE_*` vars from the developer shell from leaking into the
 * child when production relay invokes it.
 *
 * @param command       full argv including binary; typically
 *                      `listOf("claude", "-p", "--output-format", "stream-json",
 *                      "--input-format", "stream-json", ...)`.
 * @param workingDir    working directory to pin for the child.
 * @param env           exact environment map to pass; callers must
 *                      include at minimum `PATH` and `HOME`.
 * @param scope         parent scope that owns the lifecycle waiter.
 *                      Callers that don't care just pass a scope tied
 *                      to their server lifetime; the internal scope is
 *                      a child of it with a [SupervisorJob] so
 *                      writer-side failures don't propagate upward.
 * @param json          protocol Json instance (defaults to
 *                      [ProtocolJson.default]).
 */
public class ClaudeProcess(
    command: List<String>,
    workingDir: Path,
    env: Map<String, String> = DEFAULT_ENV,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    json: Json = ProtocolJson.default,
) : AutoCloseable {
    private val process: Process =
        ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(false) // GOTCHA: must be false — stderr stays separate.
            .also { pb ->
                pb.environment().apply {
                    clear()
                    putAll(env)
                }
            }.start()

    /** Typed stdout. Emits [CCEvent.Unknown] for types this client hasn't modeled yet. */
    public val events: Flow<CCEvent> =
        process.inputStream
            .asChunkedCharFlow()
            .ccEvents(json)
            .flowOn(Dispatchers.IO)

    /** Raw stderr lines. Never mixed into [events]. */
    public val stderr: Flow<String> =
        process.errorStream
            .asLineFlow()
            .flowOn(Dispatchers.IO)

    /** Completes exactly once with the child's exit code. */
    public val exit: Deferred<Int> =
        scope.async(Dispatchers.IO) {
            try {
                process.waitFor()
            } finally {
                // Nothing else holds a stake in this waiter; let the
                // coroutine scope drain naturally.
            }
        }

    private val ownedScope: CoroutineScope? =
        // Only cancel the scope we created ourselves. If a scope was
        // passed in explicitly, it stays the caller's responsibility.
        null

    private val closed = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val encodeJson: Json = json

    /**
     * Writes a single stream-json user message to the child's stdin.
     * Blocks briefly on [writeMutex] to serialize concurrent writers.
     *
     * @throws IOException if the process has been closed already.
     */
    public suspend fun writeUserMessage(message: ClaudeInput.UserMessage) {
        if (closed.get()) throw IOException("process stdin closed")
        val payload = encodeJson.encodeToString(ClaudeInput.UserMessage.serializerJson(), message) + "\n"
        writeMutex.withLock {
            try {
                process.outputStream.write(payload.toByteArray(StandardCharsets.UTF_8))
                process.outputStream.flush()
            } catch (t: IOException) {
                // Surface a canonical error so callers don't have to
                // care about platform-specific wording.
                throw IOException("failed to write to claude stdin: ${t.message}", t)
            }
        }
    }

    /**
     * Idempotent close. Closes stdin, then destroys the child if still
     * alive. Safe to call more than once.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { process.outputStream.close() }
        if (process.isAlive) {
            process.destroy()
        }
        ownedScope?.cancel()
    }

    public companion object {
        /**
         * A minimal safe default environment — `PATH` and `HOME` only.
         * Production callers should build a fuller env explicitly.
         */
        public val DEFAULT_ENV: Map<String, String> =
            buildMap {
                System.getenv("PATH")?.let { put("PATH", it) }
                System.getenv("HOME")?.let { put("HOME", it) }
            }
    }
}
