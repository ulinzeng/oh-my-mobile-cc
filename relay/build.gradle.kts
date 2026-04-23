plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.ohmymobilecc.relay.MainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.pty4j)
    implementation(libs.kermit)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}

tasks.test {
    useJUnitPlatform()
}

kover {
    reports {
        filters {
            excludes {
                // Entry-point shim is trivial glue; excluded from coverage math.
                classes("io.ohmymobilecc.relay.MainKt")
            }
        }
        verify {
            // Kover 0.9.x does NOT expose per-rule class filters, so the
            // built-in verify block enforces a conservative aggregate only
            // (80%). Per-package W1.5 thresholds — pairing ≥ 85, server
            // ≥ 85, cli ≥ 80 — are enforced by the `koverPerPackageVerify`
            // task below, which parses the XML report.
            rule("relay aggregate line coverage") {
                bound {
                    minValue.set(80)
                }
            }
        }
    }
}

// Enforce per-package thresholds that Kover 0.9 can't express in-DSL.
// The XML report is produced by `koverXmlReport`; we depend on it so
// `koverPerPackageVerify` always reads a fresh copy.
val koverPerPackageVerify by
    tasks.registering {
        dependsOn("koverXmlReport")
        val reportFile = layout.buildDirectory.file("reports/kover/report.xml")
        inputs.file(reportFile)
        doLast {
            val required =
                mapOf(
                    "io/ohmymobilecc/relay/pairing" to 85.0,
                    "io/ohmymobilecc/relay/server" to 85.0,
                    "io/ohmymobilecc/relay/cli" to 80.0,
                )
            val xml =
                javax.xml.parsers.DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(reportFile.get().asFile)
            val packages = xml.getElementsByTagName("package")
            val failures = mutableListOf<String>()
            for (i in 0 until packages.length) {
                val node = packages.item(i) as org.w3c.dom.Element
                val name = node.getAttribute("name")
                val threshold = required[name] ?: continue
                val counters = node.getElementsByTagName("counter")
                var missed = 0
                var covered = 0
                for (j in 0 until counters.length) {
                    val c = counters.item(j) as org.w3c.dom.Element
                    if (c.parentNode != node) continue
                    if (c.getAttribute("type") == "LINE") {
                        missed = c.getAttribute("missed").toInt()
                        covered = c.getAttribute("covered").toInt()
                        break
                    }
                }
                val total = missed + covered
                if (total == 0) continue
                val pct = covered * HUNDRED / total
                if (pct < threshold) {
                    failures += "$name: $pct%% < threshold $threshold%%"
                }
                logger.lifecycle("kover per-package: $name → $pct%% (need $threshold%%)")
            }
            require(failures.isEmpty()) {
                "koverPerPackageVerify failures:\n" + failures.joinToString("\n")
            }
        }
    }

val HUNDRED: Double = 100.0

// Chain: koverVerify now also runs the per-package gate.
tasks.named("koverVerify") { dependsOn(koverPerPackageVerify) }

ktlint {
    version.set("1.3.1")
    ignoreFailures.set(false)
}

detekt {
    buildUponDefaultConfig = true
}
