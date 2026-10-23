import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "ijdev-repl"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.clojars.org") { content { includeGroup("nrepl") } }
        intellijPlatform {
            defaultRepositories()
        }
    }
}
