package io.ohmymobilecc.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JVM-only smoke test: drive the CCEvent decoder with the real redacted
 * capture from `fixtures/real_captures/03-hook-bridge-approved.ndjson`.
 *
 * Hard gate — if this test breaks, every downstream component (ApprovalBridge,
 * ChatInteractor, TerminalInteractor) has a broken input parser.
 */
class CCEventFixtureTest {
    private val json = ProtocolJson.default

    @Test
    fun `decodes every line of real capture 03 without throwing`() {
        val text = loadFixture("fixtures/real_captures/03-hook-bridge-approved.ndjson")
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue(lines.size >= 50, "fixture should have dozens of lines, got ${lines.size}")

        val events =
            lines.mapIndexed { index, line ->
                try {
                    json.decodeFromString(CCEvent.serializer(), line)
                } catch (e: Exception) {
                    fail("line $index failed to decode: ${line.take(120)}...  error=${e.message}")
                }
            }

        assertTrue(
            events.any { it is CCEvent.System && it.subtype == "init" },
            "expected at least one system init event",
        )
        assertTrue(
            events.any { it is CCEvent.HookStarted && it.hookEvent == "PreToolUse" },
            "expected at least one PreToolUse hook_started",
        )
        assertTrue(
            events.any { it is CCEvent.HookResponse && it.exitCode == 0 },
            "expected at least one successful hook_response",
        )
        assertTrue(
            events.any { ev ->
                ev is CCEvent.Assistant &&
                    (ev.message?.get("content") as? JsonArray)?.any { node ->
                        (node as? JsonObject)?.get("type")?.jsonPrimitive?.content == "tool_use"
                    } == true
            },
            "expected at least one assistant message carrying a tool_use block",
        )

        val unknowns = events.filterIsInstance<CCEvent.Unknown>()
        assertTrue(
            unknowns.isEmpty(),
            "expected every line to decode to a typed variant but got ${unknowns.size} Unknown",
        )
    }

    private fun loadFixture(relative: String): String {
        val url =
            this::class.java.classLoader.getResource(relative)
                ?: error("fixture not on classpath: $relative")
        return url.openStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
