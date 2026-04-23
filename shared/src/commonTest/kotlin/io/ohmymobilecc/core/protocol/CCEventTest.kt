package io.ohmymobilecc.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Contract tests for [CCEvent] decoding / encoding, driven entirely by
 * inline JSON strings so they run on every KMP target.
 *
 * The fixture-backed smoke test lives in the jvm-only source set
 * ([CCEventFixtureTest]) because loading NDJSON files off disk is
 * platform-specific.
 */
class CCEventTest {
    private val json = ProtocolJson.default

    // ---- known-type decode ----

    @Test
    fun `decodes system init event`() {
        val line = """{"type":"system","subtype":"init","session_id":"s1"}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.System>(ev)
        assertEquals("init", ev.subtype)
        assertEquals("s1", ev.sessionId)
        assertEquals("system", ev.raw["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `decodes hook_started as HookStarted variant`() {
        val line =
            """{"type":"system","subtype":"hook_started","hook_id":"H1","hook_name":"PreToolUse:Bash","hook_event":"PreToolUse","session_id":"S1","uuid":"U1"}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.HookStarted>(ev)
        assertEquals("H1", ev.hookId)
        assertEquals("PreToolUse:Bash", ev.hookName)
        assertEquals("PreToolUse", ev.hookEvent)
        assertEquals("S1", ev.sessionId)
    }

    @Test
    fun `decodes hook_response as HookResponse variant`() {
        val line =
            """{"type":"system","subtype":"hook_response","hook_id":"H1","hook_name":"PreToolUse:Bash","hook_event":"PreToolUse","output":"","exit_code":0,"outcome":"success","uuid":"U2"}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.HookResponse>(ev)
        assertEquals("H1", ev.hookId)
        assertEquals(0, ev.exitCode)
        assertEquals("success", ev.outcome)
    }

    @Test
    fun `decodes hook_progress as HookProgress variant`() {
        val line =
            """{"type":"system","subtype":"hook_progress","hook_id":"H1","hook_name":"PreToolUse:Bash","hook_event":"PreToolUse","stdout":"partial","uuid":"U3"}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.HookProgress>(ev)
        assertEquals("H1", ev.hookId)
    }

    @Test
    fun `decodes assistant event with tool_use block`() {
        val line =
            buildString {
                append("""{"type":"assistant","message":{"id":"m1","role":"assistant",""")
                append(""""content":[{"type":"tool_use","id":"tooluse_X","name":"Bash",""")
                append(""""input":{"command":"ls","description":"list"}}]}}""")
            }
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.Assistant>(ev)
        val content = ev.message?.get("content") as? JsonArray
        assertNotNull(content)
        val first = content[0].jsonObject
        assertEquals("tool_use", first["type"]?.jsonPrimitive?.content)
        assertEquals("tooluse_X", first["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `decodes user event`() {
        val line = """{"type":"user","message":{"role":"user","content":"hello"}}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.User>(ev)
    }

    @Test
    fun `decodes result event with permission_denials`() {
        val line =
            buildString {
                append("""{"type":"result","subtype":"success","is_error":false,""")
                append(""""duration_ms":1234,"num_turns":3,"result":"ok","stop_reason":"end_turn",""")
                append(""""permission_denials":[{"tool_name":"Bash","tool_use_id":"T1",""")
                append(""""tool_input":{"command":"rm"}}]}""")
            }
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.Result>(ev)
        assertEquals("success", ev.subtype)
        assertEquals(1234L, ev.durationMs)
        val denials = ev.raw["permission_denials"] as? JsonArray
        assertNotNull(denials)
        assertEquals(1, denials.size)
    }

    @Test
    fun `decodes stream_event variant`() {
        val line = """{"type":"stream_event","data":"chunk"}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.StreamEvent>(ev)
    }

    // ---- unknown-type fallback ----

    @Test
    fun `decodes unknown top-level type as Unknown preserving raw`() {
        val line = """{"type":"not_yet_specified","foo":"bar"}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.Unknown>(ev)
        assertEquals("not_yet_specified", ev.raw["type"]?.jsonPrimitive?.content)
        assertEquals("bar", ev.raw["foo"]?.jsonPrimitive?.content)
    }

    @Test
    fun `decodes system with unknown subtype as plain System`() {
        val line = """{"type":"system","subtype":"future_thing","x":1}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.System>(ev)
        assertEquals("future_thing", ev.subtype)
    }

    @Test
    fun `decodes hook_started with unknown hook_event still as HookStarted`() {
        val line =
            """{"type":"system","subtype":"hook_started","hook_id":"H9","hook_name":"FutureHook:X","hook_event":"FutureEvent","uuid":"U9"}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.HookStarted>(ev)
        assertEquals("FutureEvent", ev.hookEvent)
    }

    @Test
    fun `decodes json without type as Unknown`() {
        val line = """{"foo":"bar"}"""
        val ev = json.decodeFromString(CCEvent.serializer(), line)
        assertIs<CCEvent.Unknown>(ev)
        assertEquals("bar", ev.raw["foo"]?.jsonPrimitive?.content)
    }

    // ---- round-trip ----

    @Test
    fun `round-trips System by preserving raw`() {
        val original =
            CCEvent.System(
                subtype = "init",
                sessionId = "s1",
                raw =
                    buildJsonObject {
                        put("type", JsonPrimitive("system"))
                        put("subtype", JsonPrimitive("init"))
                        put("session_id", JsonPrimitive("s1"))
                        put("model", JsonPrimitive("claude"))
                    },
            )
        val encoded = json.encodeToString(CCEvent.serializer(), original)
        val decoded = json.decodeFromString(CCEvent.serializer(), encoded)
        assertIs<CCEvent.System>(decoded)
        assertEquals("claude", decoded.raw["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `round-trips Unknown verbatim`() {
        val raw =
            buildJsonObject {
                put("type", JsonPrimitive("xenomorph"))
                put("species_id", JsonPrimitive(42))
            }
        val original = CCEvent.Unknown(raw = raw)
        val encoded = json.encodeToString(CCEvent.serializer(), original)
        val decoded = json.decodeFromString(CCEvent.serializer(), encoded)
        assertIs<CCEvent.Unknown>(decoded)
        assertEquals(raw, decoded.raw)
    }
}
