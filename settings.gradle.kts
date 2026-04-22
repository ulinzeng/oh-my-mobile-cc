pluginManagement {
    listOf(repositories, dependencyResolutionManagement.repositories).forEach {
        it.apply {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

rootProject.name = "oh-my-mobile-cc"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":shared")
include(":androidApp")
include(":relay")
// :iosApp — SwiftPM / Xcode project, not a Gradle module (see iosApp/ once W2.3 adds the shell)
