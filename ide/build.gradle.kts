import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "com.neex"
version = "0.1.0"

// slf4j-api is excluded everywhere: the platform ships its own copy and provider; a second one in
// the plugin classloader would shadow it ("no SLF4J providers found").
configurations.all {
    exclude(group = "org.slf4j")
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            recommended()
        }
    }
}

val repoRoot = layout.projectDirectory.dir("..")
val daemonJar = repoRoot.file("core/target/deliberatord.jar")

// The core, built by its own task (`bb core:build`); skipped when its inputs did not change.
val buildDaemonJar = tasks.register<Exec>("buildDaemonJar") {
    description = "Builds core/target/deliberatord.jar with bb core:build"
    workingDir = repoRoot.asFile
    commandLine("bb", "core:build")
    inputs.files(repoRoot.dir("core/src"), repoRoot.dir("core/resources"), repoRoot.file("core/deps.edn"), repoRoot.file("core/build.clj"))
    outputs.file(daemonJar)
}

// The jar travels in the plugin's lib/ (the zip needs no JDK on the target: the daemon runs on the IDE's own runtime).
tasks.prepareSandbox {
    dependsOn(buildDaemonJar)
    from(daemonJar) { into(pluginName.map { "$it/lib" }) }
}

tasks.runIde {
    jvmArgs("-Didea.trust.all.projects=true")
}

// 2026.2's Ultimate distribution has an obfuscated post-startup activity (com.intellij.modules.ultimate)
// that cannot be instantiated in the light test environment and fails every platform test.
tasks.test {
    dependsOn(buildDaemonJar)
    systemProperty("idea.suppressed.plugins.id", "com.intellij.modules.ultimate")
    systemProperty("deliberator.jar", daemonJar.asFile.absolutePath)
    systemProperty("deliberator.test.homes", layout.buildDirectory.dir("test-homes").get().asFile.absolutePath)
}
