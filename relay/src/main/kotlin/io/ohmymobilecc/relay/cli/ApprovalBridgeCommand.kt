package io.ohmymobilecc.relay.cli

import io.ohmymobilecc.core.protocol.ProtocolJson
import io.ohmymobilecc.relay.approval.BridgeClient
import io.ohmymobilecc.relay.approval.BridgeRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Paths

/**
 * The `relay-cli approval-bridge` subcommand that Claude Code's
 * `PreToolUse` hook spawns for every tool-use request.
 *
 * Contract (from `openspec/specs/approval-inbox/spec.md` "Hook 子进程契约"):
 *  - stdin: single JSON payload with the CC hook schema
 *    (at minimum `session_id`, `tool_name`, `tool_use_id`, `tool_input`)
 *  - stdout: single line — a CC-format `hookSpecificOutput` envelope
 *    wrapping the [io.ohmymobilecc.relay.approval.BridgeDecision] returned
 *    by the main relay over its UDS endpoint
 *  - exit code: 0 when the decision was obtained (allow OR deny);
 *    `EXIT_ERROR` on any failure (bad argv / bad stdin / IPC refused)
 *
 * Argv:
 *  - `--session-id <uuid>` (required, for diagnostics)
 *  - `--socket <path>` (required, path to the main relay's UDS)
 *
 * All I/O streams are injected so tests can drive the command in-process.
 */
public object ApprovalBridgeCommand {
    public const val EXIT_OK: Int = 0
    public const val EXIT_ERROR: Int = 2

    public fun run(
        argv: Array<String>,
        stdin: InputStream,
        stdout: PrintStream,
        stderr: PrintStream,
    ): Int {
        val envelope =
            runCatching {
                val parsed = parseArgs(argv)
                val hookJson = parseHookPayload(stdin)
                val request = buildRequest(hookJson, parsed.sessionId)
                val decision =
                    runBlocking {
                        BridgeClient.request(Paths.get(parsed.socket), request)
                    }
                wrapEnvelope(decision)
            }.getOrElse {
                stderr.println("approval-bridge: ${it.message}")
                return EXIT_ERROR
            }
        stdout.println(envelope.toString())
        return EXIT_OK
    }

    private fun parseHookPayload(stdin: InputStream): JsonObject {
        val text = stdin.readBytes().toString(Charsets.UTF_8)
        return ProtocolJson.default.parseToJsonElement(text).jsonObject
    }

    private fun buildRequest(
        hookJson: JsonObject,
        fallbackSessionId: String,
    ): BridgeRequest =
        BridgeRequest(
            sessionId = requireString(hookJson, "session_id", fallbackSessionId),
            toolName = requireString(hookJson, "tool_name"),
            toolUseId = requireString(hookJson, "tool_use_id"),
            toolInput = hookJson["tool_input"]?.jsonObject ?: buildJsonObject { },
            hookPayload = hookJson,
        )

    private fun wrapEnvelope(decision: io.ohmymobilecc.relay.approval.BridgeDecision): JsonObject =
        buildJsonObject {
            put(
                "hookSpecificOutput",
                buildJsonObject {
                    put("hookEventName", JsonPrimitive("PreToolUse"))
                    put("permissionDecision", JsonPrimitive(decision.permissionDecision))
                    put("permissionDecisionReason", JsonPrimitive(decision.permissionDecisionReason))
                    decision.updatedInput?.let { put("updatedInput", it) }
                },
            )
        }

    private data class ParsedArgs(
        val sessionId: String,
        val socket: String,
    )

    private fun parseArgs(argv: Array<String>): ParsedArgs {
        var sessionId: String? = null
        var socket: String? = null
        var i = 0
        while (i < argv.size) {
            when (argv[i]) {
                "--session-id" -> {
                    sessionId = argv.getOrNull(i + 1) ?: error("missing value for --session-id")
                    i += 2
                }
                "--socket" -> {
                    socket = argv.getOrNull(i + 1) ?: error("missing value for --socket")
                    i += 2
                }
                else -> error("unknown argument: ${argv[i]}")
            }
        }
        return ParsedArgs(
            sessionId = sessionId ?: error("--session-id is required"),
            socket = socket ?: error("--socket is required"),
        )
    }

    private fun requireString(
        obj: JsonObject,
        key: String,
        default: String? = null,
    ): String {
        val fromJson = obj[key]?.jsonPrimitive?.let { runCatching { it.content }.getOrNull() }
        return fromJson ?: default ?: error("hook payload missing required field: $key")
    }
}
