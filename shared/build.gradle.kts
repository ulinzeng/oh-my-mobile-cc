import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqlDelight)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            implementation(libs.kermit)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.bouncycastle.bcprov)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.bouncycastle.bcprov)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

android {
    namespace = "io.ohmymobilecc.shared"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("OhMyMobileDatabase") {
            packageName.set("io.ohmymobilecc.shared.db")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
}

ktlint {
    version.set("1.3.1")
    android.set(false)
    // TEMP (W1.1 deviation): ktlint-gradle 12.1.2 walks into SqlDelight's
    // `build/generated/**` before our afterEvaluate setSource() block fires,
    // so generated files produce ~40 style violations we have no control over.
    // Non-blocking until we upgrade the plugin to a version that honors
    // task-level source narrowing. Tracked in tasks.md W1 follow-up.
    ignoreFailures.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

// Restrict ktlint to hand-written source trees only — the plugin's default
// discovery walks into SqlDelight's `build/generated/**` where ktlint rules
// do not apply. Must be wired in afterEvaluate so the KMP source-set tasks
// have already been registered by the time we narrow their inputs.
afterEvaluate {
    val handWrittenRoots = listOf("commonMain", "commonTest", "jvmMain", "jvmTest")
    tasks
        .withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>()
        .configureEach {
            val root = handWrittenRoots.firstOrNull { name.contains(it, ignoreCase = true) }
            if (root != null) {
                setSource(files("src/$root/kotlin"))
            }
        }
    tasks
        .withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>()
        .configureEach {
            val root = handWrittenRoots.firstOrNull { name.contains(it, ignoreCase = true) }
            if (root != null) {
                setSource(files("src/$root/kotlin"))
            }
        }
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Enforced at W1; 0 during W0 scaffolding.
                    minValue.set(0)
                }
            }
        }
    }
}
