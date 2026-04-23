# CODEMAP: shared

_Generated 2026-04-23T10:10:52+0800 by .claude/scripts/gen-codemaps.sh_

Source root: `shared/src`

## Kotlin files

- shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/CCEvent.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramer.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/ProtocolJson.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/WireMessage.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/CCEventTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramerTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/ProtocolJsonTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/WireMessageTest.kt
- shared/src/jvmTest/kotlin/io/ohmymobilecc/core/protocol/CCEventFixtureTest.kt
- shared/src/jvmTest/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramerFixtureTest.kt

## Public top-level declarations (best-effort)

    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/CCEvent.kt:public object CCEventSerializer : KSerializer<CCEvent> {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramer.kt:public fun Flow<String>.ccEvents(json: Json = ProtocolJson.default): Flow<CCEvent> = flow {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramer.kt:public fun Flow<String>.ndjsonLines(): Flow<String> = flow {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/ProtocolJson.kt:public object ProtocolJson {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/WireMessage.kt:public object WireMessageSerializer : KSerializer<WireMessage> {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/CCEventTest.kt:class CCEventTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramerTest.kt:class NdjsonFramerTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/ProtocolJsonTest.kt:class ProtocolJsonTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/WireMessageTest.kt:class WireMessageTest {
    shared/src/jvmTest/kotlin/io/ohmymobilecc/core/protocol/CCEventFixtureTest.kt:class CCEventFixtureTest {
    shared/src/jvmTest/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramerFixtureTest.kt:class NdjsonFramerFixtureTest {
