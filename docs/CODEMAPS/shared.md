# CODEMAP: shared

_Generated 2026-04-23T17:23:29+0800 by .claude/scripts/gen-codemaps.sh_

Source root: `shared/src`

## Kotlin files

- shared/src/androidMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt
- shared/src/androidMain/kotlin/io/ohmymobilecc/core/crypto/RandomSource.kt
- shared/src/androidMain/kotlin/io/ohmymobilecc/core/crypto/Sha256.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/Base64Url.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/RandomSource.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/Sha256.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/pairing/DeviceId.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/pairing/HelloCodec.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/CCEvent.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramer.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/ProtocolJson.kt
- shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/WireMessage.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/crypto/Base64UrlTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/crypto/Ed25519Test.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/pairing/HelloCodecTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/CCEventTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramerTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/ProtocolJsonTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/WireMessageHelloTest.kt
- shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/WireMessageTest.kt
- shared/src/iosMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt
- shared/src/iosMain/kotlin/io/ohmymobilecc/core/crypto/RandomSource.kt
- shared/src/iosMain/kotlin/io/ohmymobilecc/core/crypto/Sha256.kt
- shared/src/jvmMain/kotlin/io/ohmymobilecc/core/crypto/Ed25519.kt
- shared/src/jvmMain/kotlin/io/ohmymobilecc/core/crypto/RandomSource.kt
- shared/src/jvmMain/kotlin/io/ohmymobilecc/core/crypto/Sha256.kt
- shared/src/jvmTest/kotlin/io/ohmymobilecc/core/protocol/CCEventFixtureTest.kt
- shared/src/jvmTest/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramerFixtureTest.kt

## Public top-level declarations (best-effort)

    shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/Base64Url.kt:public object Base64Url {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/crypto/RandomSource.kt:public interface RandomSource {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/pairing/HelloCodec.kt:public object HelloCodec {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/CCEvent.kt:public object CCEventSerializer : KSerializer<CCEvent> {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramer.kt:public fun Flow<String>.ccEvents(json: Json = ProtocolJson.default): Flow<CCEvent> =
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramer.kt:public fun Flow<String>.ndjsonLines(): Flow<String> =
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/ProtocolJson.kt:public object ProtocolJson {
    shared/src/commonMain/kotlin/io/ohmymobilecc/core/protocol/WireMessage.kt:public object WireMessageSerializer : KSerializer<WireMessage> {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/crypto/Base64UrlTest.kt:class Base64UrlTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/crypto/Ed25519Test.kt:class Ed25519Test {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/pairing/HelloCodecTest.kt:class HelloCodecTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/CCEventTest.kt:class CCEventTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramerTest.kt:class NdjsonFramerTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/ProtocolJsonTest.kt:class ProtocolJsonTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/WireMessageHelloTest.kt:class WireMessageHelloTest {
    shared/src/commonTest/kotlin/io/ohmymobilecc/core/protocol/WireMessageTest.kt:class WireMessageTest {
    shared/src/jvmTest/kotlin/io/ohmymobilecc/core/protocol/CCEventFixtureTest.kt:class CCEventFixtureTest {
    shared/src/jvmTest/kotlin/io/ohmymobilecc/core/protocol/NdjsonFramerFixtureTest.kt:class NdjsonFramerFixtureTest {
